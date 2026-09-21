package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Member;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.StokvelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The rounds and what each one's pot holds — the sibling of {@link LedgerService},
 * and the split between them is deliberate.
 *
 * The ledger answers *what happened, in order?*. This answers *what is the state
 * right now?*. They are not the same question and the ledger cannot answer this one:
 * a pot appears there only as a slice under a payment, labelled "cycle 2 pot", so
 * recovering the figure would mean parsing a display string back into a number. And
 * a target is not an event at all — nothing happened to make it R2,000 — so there is
 * no row for the ledger to carry it on.
 *
 * It reads and never writes. Both figures are derived on the call: there is no pot
 * column and no target column, so there is nothing to invalidate and no refresh to
 * provide.
 */
@Service
public class CycleService {

    private final StokvelConfigRepository configRepository;
    private final MemberRepository memberRepository;
    private final CycleRepository cycleRepository;
    private final AllocationRepository allocationRepository;

    public CycleService(StokvelConfigRepository configRepository,
                        MemberRepository memberRepository,
                        CycleRepository cycleRepository,
                        AllocationRepository allocationRepository) {
        this.configRepository = configRepository;
        this.memberRepository = memberRepository;
        this.cycleRepository = cycleRepository;
        this.allocationRepository = allocationRepository;
    }

    /**
     * One round, with both sides of its pot.
     *
     * Both sides rather than a percentage or a bare shortfall: the UI says "R1,500 of
     * R2,000", and a view layer that reconstructed the target from a contribution
     * amount would be a second thing in the system that knows what a contribution is.
     */
    public record CycleState(Cycle cycle, BigDecimal collected, BigDecimal target) {

        /**
         * Floored at zero. collected can legitimately exceed target: a member who
         * joined too late to be liable for this cycle can still have money land in
         * it, because findOpenCycle() puts a payment in the earliest unpaid cycle
         * whoever made it. That is a fuller pot, not a negative shortfall.
         */
        public BigDecimal shortfall() {
            return target.subtract(collected).max(BigDecimal.ZERO);
        }
    }

    /**
     * Every cycle in sequence order, with its pot counted.
     *
     * One method, not a list method plus a pot method. Two would leave the controller
     * looping over cycles to fetch each pot — N+1 queries driven from the transport
     * layer, and the controller assembling an answer that is the service's to give.
     *
     * The member list and the contribution are read once and reused across every
     * cycle. Only the pot needs a query per cycle, because it is a SUM the database
     * has to do.
     */
    @Transactional(readOnly = true)
    public List<CycleState> cycles() {
        BigDecimal contribution = configRepository.require().getContributionAmount();
        List<Member> members = memberRepository.findAllByOrderByCreatedAtAscIdAsc();
        List<Cycle> cycles = cycleRepository.findAllWithRecipient();

        // An indexed walk rather than a stream, because a cycle's target depends on
        // the cycle before it: liability runs from the day a cycle's month began,
        // which is the previous cycle's due date. The list is already in sequence
        // order, so the predecessor is free — ArrearsService has to query for it,
        // holding one cycle rather than all of them.
        List<CycleState> states = new ArrayList<>();
        for (int i = 0; i < cycles.size(); i++) {
            Cycle cycle = cycles.get(i);
            LocalDate windowOpened = i == 0
                    ? cycle.getDueDate().minusDays(1)
                    : cycles.get(i - 1).getDueDate();

            states.add(new CycleState(
                    cycle,
                    // The pot as the recipient will actually receive it (Rule 1):
                    // every allocation against this cycle, whoever paid it. This is
                    // the same query PayoutService reads before paying out, on
                    // purpose — summing only the liable members' contributions
                    // would be a second answer to "what is in the pot" that could
                    // disagree with the money actually handed over.
                    allocationRepository.sumByCycleId(cycle.getId()),
                    targetFor(windowOpened, members, contribution)));
        }
        return states;
    }

    /**
     * What this cycle was *meant* to hold: the contribution times the members who
     * were liable for it.
     *
     * Not times today's member count. Rule 3 means a member who joined in month four
     * was never liable for month two, so counting them would show month two as
     * permanently short by a contribution nobody ever owed — and that number on
     * screen would contradict the debt rows, which are written from the same
     * boundary.
     */
    private static BigDecimal targetFor(LocalDate windowOpened, List<Member> members, BigDecimal contribution) {
        long liable = members.stream().filter(member -> wasLiableFor(windowOpened, member)).count();
        return contribution.multiply(BigDecimal.valueOf(liable));
    }

    /**
     * Rule 3's boundary: liable only if they joined on or before the day the cycle's
     * month began. A member who joins partway through a cycle is out of it and
     * liable from the next one — the round they walked in on is compensated by their
     * buy-in instead (Rule 4), which is money that reaches its recipient without
     * passing through a pot.
     *
     * So a joiner lowers the target of the cycle in progress rather than raising it,
     * and the difference arrives on the ledger as a buy-in distribution. Reading the
     * screen alongside the ledger is how that is meant to be checked.
     *
     * The same question ArrearsService asks, duplicated rather than shared, because
     * the two use the answer for different things: there it decides whether to write
     * a debt row, here it decides whether to add a contribution to this cycle's
     * target. Merging them would mean a change made for a display figure could change
     * who owes money.
     *
     * UTC on both sides, because created_at is stamped from simulatedNow(), which is
     * the simulated date at UTC midnight. A local-zone conversion here is exactly how
     * a stored date silently becomes the day before.
     */
    private static boolean wasLiableFor(LocalDate windowOpened, Member member) {
        return !LocalDate.ofInstant(member.getCreatedAt(), ZoneOffset.UTC)
                .isAfter(windowOpened);
    }
}

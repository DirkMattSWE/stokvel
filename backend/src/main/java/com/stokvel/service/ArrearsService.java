package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.DebtRepository;
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
 * Answers what is owed. Reads only — it never writes a debt row and never writes an
 * allocation, which is what keeps "where money lands" a question with exactly one
 * owner (PaymentService). PayoutService writes the debt rows; this service is where
 * everyone comes to ask what they add up to.
 *
 * Nothing here is stored. A debt's amount is never decremented and carries no status
 * flag, so what is outstanding is a subtraction done at read time: the debt's amount
 * minus everything allocated against it. A flag can disagree with the allocation
 * rows; this subtraction cannot, and partial settlement falls out of it for free.
 */
@Service
public class ArrearsService {

    private final DebtRepository debtRepository;
    private final AllocationRepository allocationRepository;
    private final MemberRepository memberRepository;
    private final CycleRepository cycleRepository;
    private final StokvelConfigRepository configRepository;

    public ArrearsService(DebtRepository debtRepository,
                          AllocationRepository allocationRepository,
                          MemberRepository memberRepository,
                          CycleRepository cycleRepository,
                          StokvelConfigRepository configRepository) {
        this.debtRepository = debtRepository;
        this.allocationRepository = allocationRepository;
        this.memberRepository = memberRepository;
        this.cycleRepository = cycleRepository;
        this.configRepository = configRepository;
    }

    /** One debt and what is still owed on it — the debt row itself is unchanged. */
    public record OutstandingDebt(Debt debt, BigDecimal outstanding) {
    }

    /**
     * What this member still owes, oldest debt first, fully settled debts left out.
     *
     * The order is the order Rule 7 settles in, so it is decided here rather than by
     * the caller. Fully settled debts are dropped rather than returned as zero: an
     * allocation of zero is not a fact worth recording, and the schema's
     * CHECK (amount > 0) would reject the row anyway.
     *
     * One sum query per debt. That is N+1 in shape, but N here is the number of
     * times one member has missed a contribution — single digits in any stokvel
     * that still exists — and the alternative is a correlated subquery that is
     * harder to read than the rule it implements.
     */
    @Transactional(readOnly = true)
    public List<OutstandingDebt> outstandingFor(Member debtor) {
        List<OutstandingDebt> owed = new ArrayList<>();
        for (Debt debt : debtRepository.findAllByDebtorOrderByCreatedAtAscIdAsc(debtor)) {
            BigDecimal outstanding = debt.getAmount().subtract(allocationRepository.sumByDebtId(debt.getId()));
            if (outstanding.signum() > 0) {
                owed.add(new OutstandingDebt(debt, outstanding));
            }
        }
        return owed;
    }

    /**
     * The member's total arrears — what Rule 6 deducts from their payout when their
     * turn comes, and what the ledger shows next to their name.
     */
    @Transactional(readOnly = true)
    public BigDecimal totalOutstandingFor(Member debtor) {
        return outstandingFor(debtor).stream()
                .map(OutstandingDebt::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * One member's side of one cycle: what that cycle expected of them, and what
     * actually reached its pot.
     *
     * Both sides, not just the difference. Rule 2 only needs the shortfall, but the
     * UI wants to say "Alice paid R300 of R500", and reconstructing the expected
     * figure at the view layer would put a second thing in the system that knows
     * what a contribution is.
     */
    public record CycleShortfall(Member member, BigDecimal expected, BigDecimal paid) {

        /**
         * Floored at zero so paid exceeding expected is never a negative debt.
         * PaymentService refuses overpayment now, so this should not arise — the
         * floor stays because a guard against a negative debt row should not depend
         * on another service continuing to refuse things.
         */
        public BigDecimal shortfall() {
            return expected.subtract(paid).max(BigDecimal.ZERO);
        }

        public boolean isShort() {
            return shortfall().signum() > 0;
        }
    }

    /**
     * Who was short on one cycle, and by how much — the comparison Rule 2 turns into
     * debt rows, and the same list a cycle view shows on screen.
     *
     * This reads the allocation table, not the debt table. Past arrears are
     * irrelevant here: a member can owe R2,000 from three earlier cycles and still
     * have paid this one in full, and they earn no new debt row for it. The debt
     * table is what the caller is about to *write* from this answer.
     *
     * The recipient is included. They cannot owe themselves — CHECK (debtor_id <>
     * creditor_id) — so PayoutService skips them when writing rows, but their
     * shortfall is a real fact and the honest explanation of why their own pot came
     * up light. Leaving them out here would make the arithmetic on screen look wrong.
     *
     * One sum query per liable member rather than one GROUP BY matched up in Java.
     * A grouped query returns no row at all for a member with no allocations to this
     * cycle — which is precisely a total non-payer, the row Rule 2 most needs to
     * write. Same shape of hazard as the COALESCE on a sum over zero rows: a zero
     * here is a real, knowable zero, not missing data. N is the member count, capped
     * at twelve.
     */
    @Transactional(readOnly = true)
    public List<CycleShortfall> shortfallsFor(Cycle cycle) {
        BigDecimal expected = configRepository.require().getContributionAmount();
        LocalDate windowOpened = windowOpenedFor(cycle);

        List<CycleShortfall> comparison = new ArrayList<>();
        for (Member member : memberRepository.findAllByOrderByCreatedAtAscIdAsc()) {
            if (!wasLiableFor(windowOpened, member)) {
                continue;
            }
            BigDecimal paid = allocationRepository.sumByCycleIdAndMemberId(cycle.getId(), member.getId());
            comparison.add(new CycleShortfall(member, expected, paid));
        }
        return comparison;
    }

    /**
     * What this member still owes this one cycle's pot: zero if they were never
     * liable for it (Rule 3), otherwise the contribution less what already reached
     * the pot. The contribution half of what a member may pay.
     *
     * Same boundary and same sum as shortfallsFor, deliberately — a member's cap
     * that disagreed with the debt row they would earn for not paying would be two
     * answers to one question.
     */
    @Transactional(readOnly = true)
    public BigDecimal contributionDueFor(Cycle cycle, Member member) {
        if (!wasLiableFor(windowOpenedFor(cycle), member)) {
            return BigDecimal.ZERO;
        }
        BigDecimal expected = configRepository.require().getContributionAmount();
        BigDecimal paid = allocationRepository.sumByCycleIdAndMemberId(cycle.getId(), member.getId());
        return new CycleShortfall(member, expected, paid).shortfall();
    }

    /**
     * Rule 3's boundary. A member is liable for a cycle only if they joined on or
     * before the day that cycle's month began — a mid-cycle joiner is out of the
     * round already in progress and liable from the next one, with no proration.
     *
     * The comparison is against the cycle's <em>window</em>, not its due date. Those
     * two readings disagree for exactly one member — whoever joins partway through
     * an open cycle — and that member is the reason Rule 4 exists: they do not
     * contribute to the round they walked in on, and its recipient is compensated
     * through a buy-in distribution instead, which is money that never touches a pot.
     * Comparing against the due date would put the joiner's contribution into that
     * pot, which reaches the same recipient by a route the rules do not allow.
     *
     * It is also the enforceable reading. As a contribution the joiner could simply
     * not pay it and leave the recipient with a debt row; as a buy-in it is taken at
     * the door or they do not join.
     *
     * A member added on a due date is liable for the cycle that begins that day and
     * out of the one ending it — the payout fires that same day, so a debt row there
     * would punish them for the hour they signed up.
     *
     * UTC on both sides. created_at is stamped from StokvelConfig.simulatedNow(),
     * which is the simulated date at UTC midnight, so converting back the same way
     * is lossless — a local-zone conversion here is exactly how the stored date
     * silently becomes the day before.
     */
    private static boolean wasLiableFor(LocalDate windowOpened, Member member) {
        LocalDate joined = LocalDate.ofInstant(member.getCreatedAt(), ZoneOffset.UTC);
        return !joined.isAfter(windowOpened);
    }

    /**
     * When this cycle's month began: the due date of the cycle before it, because a
     * cycle has no start date of its own — it starts where the previous one ended.
     *
     * The first cycle of the stokvel has none, and there is no stored start date to
     * stand in: current_date moves as the clock advances (Rule 8). It falls back to
     * the day before its own due date, which keeps the liability test a single
     * comparison and is right on the merits — before the first payout nobody has
     * received anything, so there is nobody a buy-in could compensate, and a member
     * arriving in that window can still pay into the first cycle normally.
     *
     * One extra query per payout, and read off the cycle row rather than computed as
     * "one month back" — a second place that knew where a month ends would be a
     * second place to get it wrong.
     */
    private LocalDate windowOpenedFor(Cycle cycle) {
        return cycleRepository.findBySequenceNumber(cycle.getSequenceNumber() - 1)
                .map(Cycle::getDueDate)
                .orElseGet(() -> cycle.getDueDate().minusDays(1));
    }

    /** A member and everything they still owe — what the arrears endpoint answers. */
    public record MemberArrears(Member member, BigDecimal total, List<OutstandingDebt> debts) {
    }

    /**
     * The id-taking entry points, for callers that arrived over HTTP and hold an
     * untrusted number rather than a loaded row. The entity-taking methods above stay
     * as they are — PayoutService is handed its rows by the clock and has nothing to
     * look up.
     *
     * They resolve inside the transaction, which is what makes the fetch-joined
     * associations usable by the time a DTO is built: open-in-view is off, so a lazy
     * proxy escaping this boundary would fail at serialisation rather than here.
     */
    @Transactional(readOnly = true)
    public MemberArrears arrearsFor(Long memberId) {
        Member member = requireMember(memberId);
        List<OutstandingDebt> debts = outstandingFor(member);
        BigDecimal total = debts.stream()
                .map(OutstandingDebt::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new MemberArrears(member, total, debts);
    }

    @Transactional(readOnly = true)
    public List<CycleShortfall> shortfallsFor(Long cycleId) {
        return shortfallsFor(cycleRepository.findById(cycleId).orElseThrow(
                () -> new IllegalArgumentException("No cycle with id " + cycleId + ".")));
    }

    private Member requireMember(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("A member id is required.");
        }
        return memberRepository.findById(memberId).orElseThrow(
                () -> new IllegalArgumentException("No member with id " + memberId + "."));
    }
}

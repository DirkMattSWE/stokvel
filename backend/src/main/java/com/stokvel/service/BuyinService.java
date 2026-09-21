package com.stokvel.service;

import com.stokvel.model.Buyin;
import com.stokvel.model.BuyinDistribution;
import com.stokvel.model.Cycle;
import com.stokvel.model.Member;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.BuyinDistributionRepository;
import com.stokvel.repository.BuyinRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.StokvelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Rule 4. A member joining a rotation already under way pays for the rounds they
 * were not there for, and that money goes straight to the members those rounds
 * belonged to.
 *
 * The rule in one sentence: <b>one contribution for each cycle of the current
 * rotation the joiner is not liable for, paid to that cycle's recipient.</b>
 *
 * That is the whole of it, and it is smaller than Rule 4's wording suggests.
 * Rule 4 says the top-up splits "proportionally to their gap" — but each skipped
 * cycle's recipient is short by exactly one contribution, and the joiner owes
 * exactly one per skipped cycle. The two sides are equal row for row, so the
 * proportional split never runs: there is no division here, no rounding mode to
 * choose and no remainder to assign. "Proportionally" describes the outcome; it is
 * not a step.
 *
 * <h2>Why the joiner owes anything at all</h2>
 *
 * Adding a member appends a cycle (Rule 9), so the rotation gets longer the moment
 * they arrive. Everyone already in it now contributes to one more round than the
 * round they themselves receive. Alice's pot was sized for three members and she
 * will pay into four: she is one contribution short, through nobody's fault and with
 * no debt row to show for it, because nobody failed to pay anything. The buy-in is
 * what closes that — and it closes it exactly, which the tests assert by adding up
 * what every member puts in and takes out across a whole rotation.
 *
 * <h2>Why this is not a payment</h2>
 *
 * A distribution is money flowing <em>out</em> to a named member, and the schema
 * already has that shape: nothing allocates to a payout either. Routing it through
 * payment/allocation would break three ways at once — allocation's
 * {@code CHECK ((cycle_id IS NULL) <> (debt_id IS NULL))} has no third destination
 * to offer, Rule 7 would settle the joiner's (non-existent) arrears and then drop
 * the remainder into the open cycle's pot, which is the one thing Rule 4 forbids,
 * and a payment row naming Alice would claim Alice handed money over when she
 * received it. The buy-in and its distributions are the complete record; the ledger
 * reads them directly.
 *
 * <h2>Why it lives inside addMember</h2>
 *
 * {@link StokvelSetupService#addMember} is one transaction, so there is no instant
 * at which a member exists without having bought in. That is what makes the buy-in a
 * condition of joining rather than a step a caller remembers, and it is also what
 * makes the temporal ordering — buy in before any payout the joiner affects —
 * unrepresentable rather than merely agreed. It is the same move as findOpenCycle()
 * taking no date parameter: the bad state is not guarded against, it is unwritable.
 *
 * It reads and writes only buy-in rows. Nothing else in the system points at it.
 */
@Service
public class BuyinService {

    private final StokvelConfigRepository configRepository;
    private final CycleRepository cycleRepository;
    private final BuyinRepository buyinRepository;
    private final BuyinDistributionRepository distributionRepository;

    public BuyinService(StokvelConfigRepository configRepository,
                        CycleRepository cycleRepository,
                        BuyinRepository buyinRepository,
                        BuyinDistributionRepository distributionRepository) {
        this.configRepository = configRepository;
        this.buyinRepository = buyinRepository;
        this.distributionRepository = distributionRepository;
        this.cycleRepository = cycleRepository;
    }

    /**
     * The buy-in and every member it reached — the same event-and-its-slices pair as
     * {@link PaymentService.RecordedPayment}, returned for the same reason: the
     * caller wants to say "Erik bought in for R1,000, R500 each to John and Sarah"
     * without re-reading rows written three lines earlier.
     */
    public record BuyinRecorded(Buyin buyin, List<BuyinDistribution> distributions) {
    }

    /**
     * Records the joiner's buy-in, if they owe one.
     *
     * Empty is the founding case, not a failure. A member who was there from the
     * start missed no cycle, so the amount would be zero — and a zero-amount buy-in
     * is a fact about nothing, which {@code CHECK (amount > 0)} would reject anyway.
     * Nothing is written and nothing is broadcast, exactly as a payout with no
     * arrears writes no deduction.
     *
     * Takes the loaded member rather than an id: the only caller is addMember, which
     * has just saved the row. An id parameter here would be a place for a caller to
     * name somebody else.
     *
     * Transactional to join addMember's — the buy-in and the member are one event,
     * and a member added without their buy-in is the worse half of either outcome.
     */
    @Transactional
    public Optional<BuyinRecorded> recordBuyinFor(Member joiner) {
        StokvelConfig config = configRepository.require();
        List<Cycle> missed = missedCycles(cycleRepository.findAllWithRecipient(), joiner);
        if (missed.isEmpty()) {
            return Optional.empty();
        }

        // The amount is derived, never passed in. It is contribution × cycles missed
        // by construction, so it cannot disagree with the distributions underneath
        // it — the same relationship payment.amount has to its allocations. A
        // parameter here would let a joiner buy in for less than they owe and leave
        // the rotation permanently short, with no debt row to show it.
        BigDecimal contribution = config.getContributionAmount();
        Buyin buyin = buyinRepository.save(new Buyin(
                joiner,
                contribution.multiply(BigDecimal.valueOf(missed.size())),
                config.simulatedNow()));

        List<BuyinDistribution> distributions = missed.stream()
                .map(cycle -> distributionRepository.save(
                        new BuyinDistribution(buyin, cycle.getRecipient(), contribution)))
                .toList();

        return Optional.of(new BuyinRecorded(buyin, distributions));
    }

    /**
     * The cycles of the current rotation that were already under way when the joiner
     * arrived — one distribution each.
     *
     * Driven off cycles, not off payouts and not off the member list. Payouts would
     * be wrong: the cycle in progress has no payout row yet, and its recipient is
     * precisely the member Rule 4 was widened to cover. The member list is
     * unnecessary: cycle rows already carry recipient_id in rotation order, so the
     * ordering is a column, not a second query to line up against this one.
     *
     * Scoped to the current rotation — the rotation the joiner's own new cycle
     * belongs to, which is the tail. Without that filter a member joining in
     * rotation 2 would buy into rotation 1 as well, and rotation 1 was already
     * square: everyone in it paid for as many rounds as they received. From the next
     * rotation onward this returns nothing at all, because a rotation generated
     * whole has every member liable for every cycle — so "once, when you join" needs
     * no flag to enforce it.
     *
     * The joiner's own cycle is skipped explicitly. It is reachable only at the very
     * end of a stokvel, where someone joins after the final rotation has paid out
     * and their own cycle's window has therefore already opened — nobody compensates
     * themselves, which is buyin_distribution's version of
     * {@code CHECK (debtor_id <> creditor_id)}.
     */
    private static List<Cycle> missedCycles(List<Cycle> allCycles, Member joiner) {
        if (allCycles.isEmpty()) {
            return List.of();
        }

        int rotation = allCycles.get(allCycles.size() - 1).getRotationNumber();
        LocalDate joined = LocalDate.ofInstant(joiner.getCreatedAt(), ZoneOffset.UTC);

        List<Cycle> missed = new ArrayList<>();
        for (int i = 0; i < allCycles.size(); i++) {
            Cycle cycle = allCycles.get(i);
            if (cycle.getRotationNumber() != rotation
                    || cycle.getRecipient().getId().equals(joiner.getId())) {
                continue;
            }
            if (joined.isAfter(windowOpened(allCycles, i))) {
                missed.add(cycle);
            }
        }
        return missed;
    }

    /**
     * When a cycle's month began: the previous cycle's due date, because a cycle has
     * no start date of its own — it starts where the one before it ended.
     *
     * The very first cycle of the stokvel has no previous cycle, and there is no
     * stored start date to stand in for one: current_date moves as the clock
     * advances (Rule 8), so it cannot be read back. It falls back to the day before
     * its own due date, which makes the predicate a single comparison and lands on
     * the right answer for the right reason — before the first payout nobody has
     * received anything, so there is nobody to compensate, and a member arriving in
     * that window can still contribute to the first cycle in the ordinary way.
     * Without it, founding members added across two simulated days would be charged
     * a buy-in for joining their own stokvel.
     *
     * Deliberately read off the cycle rows rather than computed as "one month back".
     * A second place that knew where a month ends would be a second place to get it
     * wrong — the same argument that keeps calendar arithmetic out of ClockService.
     */
    private static LocalDate windowOpened(List<Cycle> allCycles, int index) {
        return index == 0
                ? allCycles.get(0).getDueDate().minusDays(1)
                : allCycles.get(index - 1).getDueDate();
    }
}

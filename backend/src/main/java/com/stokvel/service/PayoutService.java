package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import com.stokvel.model.Payout;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.DebtRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.PayoutRepository;
import com.stokvel.repository.StokvelConfigRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;

/**
 * Fires a payout: counts the pot, records who was short, pays the recipient gross,
 * deducts what they owe, and continues the rotation if that cycle closed it.
 * Rules 1, 2, 6 and 9 all land here, in that order.
 *
 * It never asks whether a cycle is due. {@link ClockService} decided that by calling
 * findDueCycles() and is handing over a cycle that already is. Re-checking the date
 * would put a second reader of current_date in the system (Rule 8), and — worse —
 * give this method a way to decline. Rule 1 is "fires on the due date, regardless":
 * a method that can say no is a method someone can later teach to wait for a full pot.
 *
 * Built in three passes: 3a writes the debt rows (Rule 2), 3b pays out and deducts
 * (Rules 1 and 6), 3c continues the rotation (Rule 9).
 */
@Service
public class PayoutService {

    private final StokvelConfigRepository configRepository;
    private final DebtRepository debtRepository;
    private final AllocationRepository allocationRepository;
    private final PayoutRepository payoutRepository;
    private final CycleRepository cycleRepository;
    private final MemberRepository memberRepository;
    private final ArrearsService arrearsService;
    private final PaymentService paymentService;

    public PayoutService(StokvelConfigRepository configRepository,
                         DebtRepository debtRepository,
                         AllocationRepository allocationRepository,
                         PayoutRepository payoutRepository,
                         CycleRepository cycleRepository,
                         MemberRepository memberRepository,
                         ArrearsService arrearsService,
                         PaymentService paymentService) {
        this.configRepository = configRepository;
        this.debtRepository = debtRepository;
        this.allocationRepository = allocationRepository;
        this.payoutRepository = payoutRepository;
        this.cycleRepository = cycleRepository;
        this.memberRepository = memberRepository;
        this.arrearsService = arrearsService;
        this.paymentService = paymentService;
    }

    /**
     * Fires the payout for one cycle that has come due.
     *
     * No date check, and no null check either. ClockService found this cycle through
     * findDueCycles() and is handing over a loaded row — so a null here would be a
     * programming error, not a business state, and re-reading the due date would put
     * a second reader of current_date in the system (Rule 8). There is deliberately
     * no branch in which this method declines.
     *
     * Transactional because the rows written here only make sense together: debt
     * rows, then the payout and its deduction, then the next rotation. A partial
     * payout is a worse outcome than a failed one.
     *
     * Pass 3c (rotation continuation) still to come.
     *
     * The debt rows are written before the recipient's arrears are totalled. Within
     * one payout that order changes nothing — every row written here names this
     * cycle's recipient as *creditor*, and CHECK (debtor_id <> creditor_id) means
     * they can never be among the debtors, so the two sets are disjoint. The real
     * ordering constraint is across payouts, and what guarantees it is
     * findDueCycles() returning an ordered list with each payout running to
     * completion before the next begins. This order is kept anyway: it costs
     * nothing, it is the order the rules read in, and it stays correct if a later
     * rule ever does let the two sets overlap.
     */
    @Transactional
    public PayoutOutcome firePayout(Cycle cycle) {
        recordShortfallsAsDebt(cycle);
        PayoutOutcome outcome = payRecipient(cycle);
        continueRotation(cycle);
        return outcome;
    }

    /**
     * What firing a payout produced: the gross payout row, and the arrears deduction
     * that came back out of it (Rule 6).
     *
     * Two rows in two tables, so two values — the same shape as
     * StokvelSetupService.MemberAdded and PaymentService.RecordedPayment, for the
     * same reason. The payout row alone cannot say what the recipient actually
     * received: amount_paid is gross by design, and the net is derived as
     * amount_paid minus the deduction payment. Returning the pair is what stops a
     * caller having to re-read a row this method just wrote.
     *
     * deduction is null when the recipient owed nothing. That is the ordinary case,
     * not a missing value — no deduction row is written, because a zero-amount
     * payment is a fact about nothing and CHECK (amount > 0) would reject it.
     */
    public record PayoutOutcome(Payout payout, PaymentService.RecordedPayment deduction) {

        /** Rule 6's arithmetic, derived rather than stored: gross minus what was taken back. */
        public BigDecimal netReceived() {
            return payout.getAmountPaid().subtract(deducted());
        }

        public BigDecimal deducted() {
            return deduction == null ? BigDecimal.ZERO : deduction.payment().getAmount();
        }
    }

    /**
     * Rules 1 and 6. The recipient takes their turn whatever the pot holds, and
     * whatever they owe.
     *
     * The payout row records the pot GROSS, before any deduction, and the arrears
     * come out as a separate payment flowing back the other way. Two lines on the
     * ledger rather than one net figure, which is the more honest account of what
     * happened: "John receives R1,000" and "John, auto-deducted −R200" are both
     * true, and a single R800 hides which debts were settled and to whom.
     *
     * The cap is a min on the deduction, never a clamp on the payout — that is what
     * makes Rule 6's max(0, pot - owed) fall out with no branch. It also means
     * recordArrearsDeduction can never be handed more than the member owes, so its
     * refusal is unreachable by construction rather than by agreement.
     *
     * No deduction row when nothing is owed or the pot is empty. A zero-amount
     * payment is a fact about nothing, and CHECK (amount > 0) would reject it — a
     * payout with no deduction is the ordinary case, not an edge case.
     */
    private PayoutOutcome payRecipient(Cycle cycle) {
        Member recipient = cycle.getRecipient();
        BigDecimal pot = allocationRepository.sumByCycleId(cycle.getId());

        // Saved before the deduction, and not for tidiness: the deduction payment
        // carries payout_id back to this row, so the row has to exist to be pointed at.
        Payout payout = payoutRepository.save(
                new Payout(cycle, recipient, pot, configRepository.require().simulatedNow()));

        BigDecimal deduction = pot.min(arrearsService.totalOutstandingFor(recipient));
        if (deduction.signum() == 0) {
            return new PayoutOutcome(payout, null);
        }

        // The RecordedPayment is kept, not discarded. It is the only place the names
        // of the settled creditors exist — payment.amount can say R200 came back out,
        // but not who it reached — and re-reading it afterwards would be querying for
        // a row written three lines earlier.
        return new PayoutOutcome(payout, paymentService.recordArrearsDeduction(payout, deduction));
    }

    /**
     * Rule 9. A rotation's cycles are generated as a whole, and only once the
     * previous rotation has finished running — never in advance.
     *
     * Whether a cycle closed its rotation is only knowable after its payout has
     * fired, which is why this lives here and not in ClockService. It also has to
     * run inline, inside this call: findDueCycles() only returns cycles that existed
     * when it ran, so a clock advance large enough to close one rotation and reach
     * into the next depends on these rows being written before the caller looks
     * again.
     *
     * The signal is findOpenCycle() coming back empty — the rotation has no cycle
     * left that has not paid out. Deliberately not a date query and not a count of
     * members: firePayout never reads current_date (Rule 8), and a late joiner has
     * already had their cycle appended to this rotation's tail by addMember, so
     * there is no arithmetic to get wrong.
     *
     * Empty means the rotation is complete, not that the stokvel is over. Only when
     * the rotation just closed is the last one does nothing get written.
     */
    private void continueRotation(Cycle closed) {
        if (cycleRepository.findOpenCycle().isPresent()) {
            return;
        }

        StokvelConfig config = configRepository.require();
        if (closed.getRotationNumber() >= config.getRotationCount()) {
            return;
        }

        // Safe to chain off the cycle just closed: if it were not the tail, the
        // cycles after it would be unpaid and findOpenCycle() would have returned one.
        int sequenceNumber = closed.getSequenceNumber();
        LocalDate dueDate = closed.getDueDate();
        int rotationNumber = closed.getRotationNumber() + 1;

        // Read live, at this moment — which is the whole of Rule 3's "back of the
        // rotation". A member who joined during the rotation just finished is simply
        // present in this list, and needs no special case to appear in the new one.
        for (Member recipient : memberRepository.findAllByOrderByCreatedAtAscIdAsc()) {
            sequenceNumber++;
            dueDate = endOfMonth(dueDate.plusMonths(1));
            cycleRepository.save(new Cycle(sequenceNumber, rotationNumber, dueDate, recipient));
        }
    }

    /**
     * Cycle boundaries are end of month (Rule 8). Deliberately duplicated from
     * StokvelSetupService rather than shared: that one appends a single cycle to the
     * current rotation and this one opens a new rotation, so they are two rules that
     * happen to agree on the calendar, not one rule in two places.
     */
    private static LocalDate endOfMonth(LocalDate date) {
        return date.with(TemporalAdjusters.lastDayOfMonth());
    }

    /**
     * Rule 2. Everyone who was liable for this cycle and did not cover their
     * contribution owes the difference to the member whose turn it was — a named
     * creditor, not "the group" in the abstract.
     *
     * The recipient is skipped rather than filtered out upstream, because their
     * shortfall is not nothing: it is why their own pot came up light. They simply
     * receive less, which is the penalty already paid, and CHECK (debtor_id <>
     * creditor_id) means there is no row to write for it.
     *
     * created_at comes from the simulated clock (Rule 8). It is business data here,
     * not bookkeeping: Rule 7 settles debts oldest-first, so this timestamp decides
     * the order a later payment pays them off in.
     */
    private void recordShortfallsAsDebt(Cycle cycle) {
        Member recipient = cycle.getRecipient();
        Instant now = configRepository.require().simulatedNow();

        for (ArrearsService.CycleShortfall shortfall : arrearsService.shortfallsFor(cycle)) {
            if (!shortfall.isShort()) {
                continue;                       // covered their contribution — nothing owed
            }
            if (shortfall.member().getId().equals(recipient.getId())) {
                continue;                       // their own cycle — nobody owes themselves
            }
            debtRepository.save(new Debt(
                    shortfall.member(), recipient, cycle, shortfall.shortfall(), now));
        }
    }
}

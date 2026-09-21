package com.stokvel.service;

import com.stokvel.model.Cycle;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.StokvelConfigRepository;
import com.stokvel.service.PayoutService.PayoutOutcome;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Rule 8 — the clock. The one place current_date is written, and the only service
 * that reads it.
 *
 * It moves time and then asks what has become due. It does not pay anyone out: that
 * is PayoutService's job, and it happens because a due date has passed, not because
 * anyone asked for it. The demo line depends on that being literally true — "this
 * button doesn't pay anyone, it moves time; the payout fires on its own."
 *
 * Nothing here simulates a payment either. A pot is whatever members actually paid
 * in before the date passed, and Rule 1 pays out that amount whatever it is, R0
 * included.
 *
 * advanceClock is the demo's entry point and would not exist in production.
 * checkDue is the half that would: a scheduler calls it against real time and every
 * rule below it runs unchanged. That is what makes "same code path in production" a
 * fact rather than an aspiration, and it is the whole reason these are two methods.
 *
 * It knows about dates and cycles, and nothing else. Rotations, pots, debts and
 * deductions are all below this line — a payout hands back a PayoutOutcome and the
 * clock passes it straight on without opening it.
 */
@Service
public class ClockService {

    private final StokvelConfigRepository configRepository;
    private final CycleRepository cycleRepository;
    private final PayoutService payoutService;

    public ClockService(StokvelConfigRepository configRepository,
                        CycleRepository cycleRepository,
                        PayoutService payoutService) {
        this.configRepository = configRepository;
        this.cycleRepository = cycleRepository;
        this.payoutService = payoutService;
    }

    /**
     * Moves the clock to a target date and returns the payouts that fired as a
     * result, oldest first.
     *
     * Takes a target date rather than a number of months. The demo hops to a point
     * on the calendar, and the cycle rows already carry their own due dates, so
     * there is no month arithmetic to do here — a second place in the codebase that
     * knew where a month ends would be a second place to get it wrong.
     *
     * Multi-month jumps are supported deliberately, not merely tolerated. Skipping
     * three months loses nobody's money: it writes debt rows, and Rule 7 lets a
     * single later payment walk backwards through every month that was missed. In
     * production the same shape is just the app having been down for a while.
     *
     * Known cost, accepted: created_at everywhere comes from the simulated clock, so
     * a multi-month jump stamps every row it writes with the target date, and
     * January's debts then read as dated March. Settlement order is unaffected —
     * debts are ordered by created_at then id, and id is monotonic — and advancing a
     * month at a time, which is how the demo runs, never reaches it.
     *
     * One transaction for the whole advance, payouts included. The clock moving and
     * the payouts it caused are one event; a date that moved past payouts which
     * never fired would be the worst of the available outcomes.
     */
    @Transactional
    public List<PayoutOutcome> advanceClock(LocalDate target) {
        StokvelConfig config = configRepository.require();

        // Time is not append-only, but it is monotonic. Rewinding would leave debts
        // and payouts stamped after "today" — not corruption, since a payout row
        // closes its cycle and nothing can re-fire, but an incoherent ledger. To
        // start a fresh simulation, delete stokvel.db: the database is file-backed
        // precisely so that restarting cannot reset the clock.
        if (target.isBefore(config.getCurrentDate())) {
            throw new IllegalArgumentException(
                    "The clock cannot move backwards: current date is " + config.getCurrentDate()
                            + ", asked to move to " + target + ".");
        }

        config.setCurrentDate(target);
        configRepository.save(config);

        return checkDue();
    }

    /**
     * Fires every cycle that has come due and not yet paid out, oldest first.
     *
     * It re-queries after each payout instead of iterating one snapshot, because the
     * set of due cycles can *grow* mid-loop. A payout that closes a rotation
     * generates the next rotation's cycles inline (Rule 9 — rotations are generated
     * one at a time, never in advance), and those rows did not exist when the first
     * query ran. A large enough jump makes some of them due immediately. A for-each
     * over a single findDueCycles() result would skip those payouts in silence,
     * which is the worst kind of wrong: a ledger merely missing entries still looks
     * fine.
     *
     * Taking the front of the list each time is what keeps them in date order, and
     * that order is load-bearing for a second, separate reason: each payout writes
     * debt rows that the next payout's arrears deduction has to see (Rule 6). Cycle
     * 2 has to finish completely before cycle 3 begins.
     *
     * The loop terminates because firePayout always writes a payout row and
     * payout.cycle_id is UNIQUE, so a cycle it was handed can never come back from
     * findDueCycles() a second time.
     *
     * current_date is read once, here. Nothing below this line reads it again —
     * PayoutService deliberately never re-checks whether a cycle is due, because
     * Rule 1 fires regardless and a method that can ask is a method that can decline.
     */
    @Transactional
    public List<PayoutOutcome> checkDue() {
        LocalDate today = configRepository.require().getCurrentDate();

        List<PayoutOutcome> fired = new ArrayList<>();
        for (List<Cycle> due = cycleRepository.findDueCycles(today);
             !due.isEmpty();
             due = cycleRepository.findDueCycles(today)) {

            fired.add(payoutService.firePayout(due.getFirst()));
        }
        return fired;
    }

    /** The clock as it stands, for the UI's date display. */
    public StokvelConfig currentConfig() {
        return configRepository.require();
    }
}

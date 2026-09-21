package com.stokvel.service;

import com.stokvel.model.Allocation;
import com.stokvel.model.Buyin;
import com.stokvel.model.BuyinDistribution;
import com.stokvel.model.Debt;
import com.stokvel.model.Payment;
import com.stokvel.model.Payout;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.BuyinDistributionRepository;
import com.stokvel.repository.BuyinRepository;
import com.stokvel.repository.DebtRepository;
import com.stokvel.repository.PaymentRepository;
import com.stokvel.repository.PayoutRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * The ledger — every fact the system holds, in the order it happened.
 *
 * It writes nothing and stores nothing, which is why "Ledger" is on CLAUDE.md's list
 * of things that are deliberately not tables: a ledger table would be a second copy
 * of history, and a second copy is a thing that can disagree with the first.
 *
 * It is not a filtered view either. It shows everything, always. The moment it began
 * deciding what was relevant it would become something a member could accuse of
 * hiding a line, and the demo line it exists to serve is the opposite: "the software
 * can't force anyone to pay — what it does is make sure nobody can hide or dispute
 * that they owe it."
 *
 * It answers one question, *what happened, in order?*, and deliberately not the other
 * one, *what is the state right now?* Whose turn it is, what the pot holds and when
 * it falls due are read from the cycle endpoints. Keeping those apart is what stops
 * this becoming the object every screen reads and every change has to touch.
 *
 * Its shape is not an invention: an event with its detail underneath is the
 * payment/allocation split the schema already has, and buyin/buyin_distribution is
 * that same shape a second time. The ledger reads it back rather than imposing
 * anything new.
 */
@Service
public class LedgerService {

    private final PaymentRepository paymentRepository;
    private final AllocationRepository allocationRepository;
    private final DebtRepository debtRepository;
    private final PayoutRepository payoutRepository;
    private final BuyinRepository buyinRepository;
    private final BuyinDistributionRepository distributionRepository;

    public LedgerService(PaymentRepository paymentRepository,
                         AllocationRepository allocationRepository,
                         DebtRepository debtRepository,
                         PayoutRepository payoutRepository,
                         BuyinRepository buyinRepository,
                         BuyinDistributionRepository distributionRepository) {
        this.paymentRepository = paymentRepository;
        this.allocationRepository = allocationRepository;
        this.debtRepository = debtRepository;
        this.payoutRepository = payoutRepository;
        this.buyinRepository = buyinRepository;
        this.distributionRepository = distributionRepository;
    }

    /**
     * What kind of fact a line records, and — through the rank — how lines from the
     * same simulated day sort against each other.
     *
     * The rank is load-bearing, not decoration. Every row written on one simulated
     * day carries a byte-identical created_at, because the clock is a date and
     * simulatedNow() is its midnight (Rule 8). Sorting on the timestamp alone would
     * leave a payout and the deduction that came out of it in whichever order the
     * merge happened to produce, and "R1,000 / -R200" would come out backwards as
     * often as not. The rank is the order these things actually occur inside a
     * payout: money arrives, shortfalls are recorded, the recipient is paid, then
     * what they owed is taken straight back out.
     *
     * The consequence, stated rather than left to be found on stage: within a single
     * simulated day the order is *causal*, not the order the calls arrived in. A
     * member who pays after that day's payout has already fired is still listed
     * before it. That is the right trade — it is what keeps a payout and its
     * deduction inseparable, which is the pair the whole of Rule 6 is explained with,
     * and arrival order would need a time of day on the clock. A clock with a time of
     * day is a clock somebody has to pick the cut-off hour for.
     */
    public enum EntryType {
        /** A member handed money over. Rule 7 decides where it landed. */
        PAYMENT(0),
        /**
         * A joiner paid for the rounds they were not there for, and it went straight
         * out again to the members those rounds belonged to (Rule 4).
         *
         * Ranked with the inflows, above DEBT and PAYOUT, because a buy-in can
         * compensate a cycle whose payout fires the very same simulated day — a
         * member joining on a due date is out of that cycle and tops up its
         * recipient. Ranked below it, the ledger would show the recipient being paid
         * before the money that made up their pot's shortfall arrived.
         */
        BUYIN(1),
        /** Someone was short on a cycle and now owes a named creditor (Rule 2). */
        DEBT(2),
        /** A cycle came due and its recipient took their turn, gross (Rules 1, 6). */
        PAYOUT(3),
        /** The arrears taken straight back out of that payout (Rule 6). */
        DEDUCTION(4);

        private final int rank;

        EntryType(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    /**
     * Where one slice of a payment went — detail underneath a PAYMENT or DEDUCTION
     * line, never a line of its own.
     *
     * An allocation has no created_at. It can only borrow its payment's, which means
     * it could never sort anywhere except immediately beside it. A row that cannot
     * move independently is not an event; it is detail of one. Money arriving is the
     * fact, where it landed is the breakdown.
     */
    public record LedgerSlice(BigDecimal amount, String destination) {
    }

    /**
     * One line on screen.
     *
     * Flat and uniform on purpose: the frontend renders a list, and a shape that
     * varied per type would push the decision of what each row means into the
     * client, where it would become a second implementation of rules that live here.
     *
     * It carries no JPA entities — only strings, amounts and an instant — so it is
     * already the wire shape. A separate LedgerResponse would be a field-for-field
     * copy under another name, which is the ceremony this codebase refuses. The rule
     * that DTOs cross the wire exists to stop *entities* being serialised (Debt has
     * two foreign keys back to Member, and Jackson follows them forever); nothing
     * here is one.
     *
     * counterparty is whoever the money faced — the creditor on a debt, nobody on a
     * plain payment. Null when there is none, which is a real answer rather than
     * missing data.
     */
    public record LedgerEntry(Instant at,
                              EntryType type,
                              String member,
                              String counterparty,
                              BigDecimal amount,
                              Integer cycleSequenceNumber,
                              String description,
                              List<LedgerSlice> slices) {
    }

    /**
     * Every fact, oldest first.
     *
     * Three queries for the lines, one for the slices, and a sort — rather than one
     * SQL UNION. JPQL has no UNION across unrelated entities, and a native one hands
     * back untyped columns to map by hand, which would be a second place deciding
     * what a row means. The row counts here are a demo's worth.
     *
     * The allocations are fetched once and grouped by payment id rather than looked
     * up per payment. The ledger renders every payment there has ever been, so the
     * per-payment version would be N+1 by construction.
     *
     * Read-only transaction: nothing here writes, and declaring that keeps it true.
     */
    @Transactional(readOnly = true)
    public List<LedgerEntry> getLedger() {
        Map<Long, List<Allocation>> slicesByPayment = allocationRepository.findAllForLedger().stream()
                .collect(Collectors.groupingBy(allocation -> allocation.getPayment().getId()));
        Map<Long, List<BuyinDistribution>> topUpsByBuyin = distributionRepository.findAllForLedger().stream()
                .collect(Collectors.groupingBy(topUp -> topUp.getBuyin().getId()));

        List<LedgerEntry> entries = new ArrayList<>();
        for (Payment payment : paymentRepository.findAllForLedger()) {
            entries.add(toEntry(payment, slicesByPayment.getOrDefault(payment.getId(), List.of())));
        }
        for (Debt debt : debtRepository.findAllForLedger()) {
            entries.add(toEntry(debt));
        }
        for (Payout payout : payoutRepository.findAllForLedger()) {
            entries.add(toEntry(payout));
        }
        for (Buyin buyin : buyinRepository.findAllForLedger()) {
            entries.add(toEntry(buyin, topUpsByBuyin.getOrDefault(buyin.getId(), List.of())));
        }

        entries.sort(Comparator.comparing(LedgerEntry::at)
                .thenComparingInt(entry -> entry.type().rank()));
        return entries;
    }

    /**
     * A payment, with where its money went underneath it.
     *
     * payout_id being set is what makes it a DEDUCTION rather than a PAYMENT — an
     * explicit link, never inferred from timing or from the fact that it settled
     * debts. A member can settle arrears voluntarily on the very same day, and that
     * is a different event: one is paying a debt, the other is having it taken off
     * you.
     */
    private LedgerEntry toEntry(Payment payment, List<Allocation> allocations) {
        boolean automatic = payment.getPayout() != null;
        String name = payment.getMember().getName();

        return new LedgerEntry(
                payment.getCreatedAt(),
                automatic ? EntryType.DEDUCTION : EntryType.PAYMENT,
                name,
                null,
                payment.getAmount(),
                null,
                automatic ? name + " — auto-deducted from their payout" : name + " paid in",
                allocations.stream().map(LedgerService::toSlice).toList());
    }

    /** Rule 2 — a named creditor, not "the group" in the abstract. */
    private LedgerEntry toEntry(Debt debt) {
        int cycle = debt.getCycle().getSequenceNumber();
        return new LedgerEntry(
                debt.getCreatedAt(),
                EntryType.DEBT,
                debt.getDebtor().getName(),
                debt.getCreditor().getName(),
                debt.getAmount(),
                cycle,
                debt.getDebtor().getName() + " fell short on cycle " + cycle
                        + " and owes " + debt.getCreditor().getName(),
                List.of());
    }

    /**
     * The payout as its own line, carrying GROSS. Its deduction is a separate line
     * that sorts directly after it, which is the whole point: "John receives R1,000"
     * and "John — auto-deducted R200" are both true and both visible, where a single
     * net R800 would hide which debts were settled and to whom.
     */
    private LedgerEntry toEntry(Payout payout) {
        int cycle = payout.getCycle().getSequenceNumber();
        return new LedgerEntry(
                payout.getCreatedAt(),
                EntryType.PAYOUT,
                payout.getRecipient().getName(),
                null,
                payout.getAmountPaid(),
                cycle,
                payout.getRecipient().getName() + " received cycle " + cycle + "'s pot",
                List.of());
    }

    /**
     * Rule 4. One line for the buy-in, with the members it reached underneath it.
     *
     * The same event-with-its-detail shape as a payment and its allocations, and for
     * the same reason: a buyin_distribution row has no created_at of its own, so it
     * could only ever borrow its buy-in's and sort beside it. A row that cannot move
     * independently is detail of an event, not an event. That the ledger reads this
     * way without any new machinery is the schema being read back rather than a
     * structure imposed on it.
     *
     * No counterparty on the line, unlike a debt: a buy-in faces several members at
     * once, and naming one of them would be choosing. They are all in the slices.
     */
    private LedgerEntry toEntry(Buyin buyin, List<BuyinDistribution> topUps) {
        String name = buyin.getMember().getName();
        return new LedgerEntry(
                buyin.getCreatedAt(),
                EntryType.BUYIN,
                name,
                null,
                buyin.getAmount(),
                null,
                name + " bought into the rotation already under way",
                topUps.stream().map(LedgerService::toSlice).toList());
    }

    /**
     * Where one slice of a buy-in landed: with a member whose own round was sized
     * for a smaller rotation than the one they now pay into (Rule 4).
     *
     * Every slice is exactly one contribution, because the joiner owes one per cycle
     * they missed and each of those cycles' recipients is short by one. There is no
     * proportional division to render, which is why this says who rather than how
     * much of what.
     */
    private static LedgerSlice toSlice(BuyinDistribution topUp) {
        return new LedgerSlice(topUp.getAmount(), "top-up to " + topUp.getRecipient().getName());
    }

    /**
     * Exactly one of cycle / debt is set on an allocation, which the schema enforces
     * with a CHECK. The destination is spelled out here rather than left for the
     * client to infer from which field came back null — the server owns what a row
     * means.
     */
    private static LedgerSlice toSlice(Allocation allocation) {
        return allocation.getCycle() != null
                ? new LedgerSlice(allocation.getAmount(),
                        "cycle " + allocation.getCycle().getSequenceNumber() + " pot")
                : new LedgerSlice(allocation.getAmount(),
                        "debt to " + allocation.getDebt().getCreditor().getName());
    }
}

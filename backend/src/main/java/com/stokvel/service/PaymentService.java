package com.stokvel.service;

import com.stokvel.model.Allocation;
import com.stokvel.model.Cycle;
import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import com.stokvel.model.Payment;
import com.stokvel.model.Payout;
import com.stokvel.model.StokvelConfig;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.CycleRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.PaymentRepository;
import com.stokvel.repository.StokvelConfigRepository;
import com.stokvel.service.ArrearsService.OutstandingDebt;
import com.stokvel.websocket.LedgerBroadcaster;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * Records that money arrived, and where it landed. Two tables, one operation: a
 * payment with no allocation is money the system cannot account for, so both writes
 * happen here, in one transaction.
 *
 * There is no updatePayment and no deletePayment, now or ever. A payment is a fact
 * that happened; the correction for a wrong one is another fact, not an edit.
 *
 * Rule 7 lives in {@link #settleDebts}: a payment clears what the member owes,
 * oldest debt first, and only the remainder reaches the current cycle's pot. Note
 * what did not have to change for that rule to exist — the payment row, the pot
 * query, the ledger. The pot was always a sum over allocations, never a running
 * total on a payment, so redirecting allocations redirects everything downstream.
 */
@Service
public class PaymentService {

    /** Money is cents-precise. DECIMAL(19,2) in the schema; SQLite will not enforce it. */
    private static final int CENTS = 2;

    private final StokvelConfigRepository configRepository;
    private final MemberRepository memberRepository;
    private final CycleRepository cycleRepository;
    private final PaymentRepository paymentRepository;
    private final AllocationRepository allocationRepository;
    private final ArrearsService arrearsService;
    private final LedgerBroadcaster broadcaster;

    public PaymentService(StokvelConfigRepository configRepository,
                          MemberRepository memberRepository,
                          CycleRepository cycleRepository,
                          PaymentRepository paymentRepository,
                          AllocationRepository allocationRepository,
                          ArrearsService arrearsService,
                          LedgerBroadcaster broadcaster) {
        this.configRepository = configRepository;
        this.memberRepository = memberRepository;
        this.cycleRepository = cycleRepository;
        this.paymentRepository = paymentRepository;
        this.allocationRepository = allocationRepository;
        this.arrearsService = arrearsService;
        this.broadcaster = broadcaster;
    }

    /** A payment and every slice of it, with debt and creditor already loaded. */
    public record RecordedPayment(Payment payment, List<Allocation> allocations) {
    }

    /**
     * A member pays. The amount is whatever they actually paid, up to what they owe
     * right now (see {@link MaxPayable}) — anything above that is refused, not
     * potted. Paying short is not refused at the door: it becomes a debt row when
     * the cycle's due date arrives and the pot is counted (Rule 2).
     *
     * Rule 7 in order: what they owe is settled first, oldest debt first, and what
     * survives that goes to the open cycle's pot.
     *
     * payout is null: this is a voluntary payment. The system-generated arrears
     * deduction is the only kind that carries a payout_id.
     */
    @Transactional
    public RecordedPayment recordPayment(Long memberId, BigDecimal amount) {
        StokvelConfig config = configRepository.require();
        Member member = requireMember(memberId);
        requireMoney(amount);

        Optional<Cycle> openCycle = cycleRepository.findOpenCycle();
        List<OutstandingDebt> owed = arrearsService.outstandingFor(member);
        BigDecimal max = calculateMaxPayable(member, openCycle, owed).total();
        if (max.signum() == 0) {
            throw new IllegalStateException(member.getName() + " owes nothing right now — there is nothing to pay.");
        }
        if (amount.compareTo(max) > 0) {
            throw new IllegalStateException("That is more than " + member.getName() + " owes: at most R"
                    + max.toPlainString() + " can be paid right now.");
        }

        Payment payment = paymentRepository.save(
                new Payment(member, amount, null, config.simulatedNow()));

        // After the cap, a remainder can only be what is left of this cycle's
        // contribution, so an open cycle the member is liable for exists.
        BigDecimal remainder = settleDebts(payment, owed, amount);
        if (remainder.signum() > 0) {
            allocate(payment, openCycle.orElseThrow(), null, remainder);
        }


        // Broadcast at the outermost mutating method, not at every one.
        // recordArrearsDeduction and firePayout are only ever reached from inside
        // another service's transaction, so pushing from there would send clients a
        // half-finished picture — debts written but no payout yet — and then replace
        // it a moment later. One push per user action is fewer messages and a state
        // that was never incoherent.
        broadcaster.broadcast();
        return new RecordedPayment(payment, allocationRepository.findByPaymentId(payment.getId()));
    }

    /**
     * The most a member can pay right now, in its two halves: arrears, which Rule 7
     * settles first, and what is left of the open cycle's contribution. Anything
     * above the total is refused rather than potted — the system has no concept of
     * credit, and a pot that grew past its target would hand the recipient a bonus.
     */
    public record MaxPayable(BigDecimal arrears, BigDecimal contributionDue) {
        public BigDecimal total() {
            return arrears.add(contributionDue);
        }
    }

    /** The prefill and the input cap on the payment form — the same arithmetic recordPayment refuses by. */
    @Transactional(readOnly = true)
    public MaxPayable maxPayable(Long memberId) {
        Member member = requireMember(memberId);
        return calculateMaxPayable(member, cycleRepository.findOpenCycle(), arrearsService.outstandingFor(member));
    }

    private MaxPayable calculateMaxPayable(Member member, Optional<Cycle> openCycle, List<OutstandingDebt> owed) {
        BigDecimal arrears = owed.stream()
                .map(OutstandingDebt::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal contributionDue = openCycle
                .map(cycle -> arrearsService.contributionDueFor(cycle, member))
                .orElse(BigDecimal.ZERO);
        return new MaxPayable(arrears, contributionDue);
    }

    /**
     * Rule 6's deduction: the arrears a member has are taken out of the payout they
     * are receiving. It is a real payment row with payout_id set, and its allocations
     * settle their debts — which is what keeps every allocation attached to a payment
     * and leaves one code path instead of a nullable payment_id.
     *
     * PayoutService decides the amount, capped at what is owed. The check that
     * nothing is left over turns "fully allocated by construction" from a claim in a
     * comment into something that cannot silently stop being true.
     */
    @Transactional
    public RecordedPayment recordArrearsDeduction(Payout payout, BigDecimal amount) {
        StokvelConfig config = configRepository.require();
        requireMoney(amount);

        Member recipient = payout.getRecipient();
        Payment payment = paymentRepository.save(
                new Payment(recipient, amount, payout, config.simulatedNow()));

        BigDecimal remainder = settleDebts(payment, arrearsService.outstandingFor(recipient), amount);
        if (remainder.signum() > 0) {
            throw new IllegalStateException("A deduction cannot exceed what the member owes: "
                    + remainder.toPlainString() + " had nowhere to settle.");
        }

        return new RecordedPayment(payment, allocationRepository.findByPaymentId(payment.getId()));
    }

    /**
     * Rule 7. Walks the member's outstanding debts oldest first, writing one
     * allocation per debt it reaches, and returns what is left over.
     *
     * A debt is never decremented and never marked settled — the allocation row is
     * the whole record of the settlement, and the debt's outstanding balance is that
     * subtraction done again next time anyone asks.
     */
    private BigDecimal settleDebts(Payment payment, List<OutstandingDebt> owed, BigDecimal amount) {
        BigDecimal left = amount;
        for (OutstandingDebt debt : owed) {
            if (left.signum() == 0) {
                break;
            }
            BigDecimal slice = left.min(debt.outstanding());
            allocate(payment, null, debt.debt(), slice);
            left = left.subtract(slice);
        }
        return left;
    }

    /**
     * Writes one slice of a payment to one destination, and is the service-layer
     * half of "exactly one of cycle_id / debt_id is set" — the schema's CHECK is the
     * belt, this is the braces.
     */
    private Allocation allocate(Payment payment, Cycle cycle, Debt debt, BigDecimal amount) {
        if ((cycle == null) == (debt == null)) {
            throw new IllegalStateException(
                    "An allocation lands in a cycle's pot or against a debt — one, never both or neither.");
        }
        return allocationRepository.save(new Allocation(payment, cycle, debt, amount));
    }

    private Member requireMember(Long memberId) {
        if (memberId == null) {
            throw new IllegalArgumentException("A payment needs a member.");
        }
        return memberRepository.findById(memberId).orElseThrow(
                () -> new IllegalArgumentException("No member with id " + memberId + "."));
    }

    /**
     * Rejects more precision than money has, rather than rounding it. Rounding would
     * store something other than what the caller said happened, and every total in
     * this system is derived by summing these rows back up — a cent invented here is
     * a cent that shows up in a pot, a payout and a debt balance.
     */
    private static void requireMoney(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("A payment must be more than zero.");
        }
        if (amount.stripTrailingZeros().scale() > CENTS) {
            throw new IllegalArgumentException("A payment is cents-precise: " + amount.toPlainString()
                    + " has more than two decimal places.");
        }
    }
}

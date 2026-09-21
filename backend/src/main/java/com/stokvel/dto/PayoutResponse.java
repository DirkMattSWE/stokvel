package com.stokvel.dto;

import com.stokvel.model.Payout;
import com.stokvel.service.PaymentService.RecordedPayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A payout, and the arrears deduction that came out of it (Rule 6).
 *
 * Three figures, not one. amountPaid is GROSS — what the pot held. deducted is the
 * arrears payment that flowed back the other way. netReceived is the subtraction.
 * Sending only the net would be a smaller payload and a worse answer: it hides which
 * debts were settled and to whom, which is the part that makes the rule defensible
 * to the member losing money.
 *
 *   PAYOUT   John receives          R1,000
 *   PAYMENT  John — auto-deducted     -R200   (settles debt to Sarah, Thabo)
 *                                   --------
 *                                     R800
 *
 * netReceived is derived from payment.amount rather than re-summed from the
 * allocations underneath it. The two agree by construction — a deduction is fully
 * allocated to debts or it is refused — and "R200 was deducted" is the honest framing
 * of a display figure, rather than one reconstructed from the debt accounting. The
 * settled list is still carried, because which creditors is the one thing the amount
 * alone cannot say.
 *
 * deducted is zero and settled is empty on an ordinary payout. That is the common
 * case, not a missing value: no deduction payment row is written when a member owes
 * nothing, and none should be invented here to make the shape uniform.
 */
public record PayoutResponse(Long id,
                             Long cycleId,
                             Integer cycleSequenceNumber,
                             Long recipientId,
                             String recipientName,
                             BigDecimal amountPaid,
                             BigDecimal deducted,
                             BigDecimal netReceived,
                             List<AllocationResponse> settled,
                             Instant createdAt) {

    /** An ordinary payout — nothing was owed, so nothing came back out. */
    public static PayoutResponse from(Payout payout) {
        return from(payout, null);
    }

    public static PayoutResponse from(Payout payout, RecordedPayment deduction) {
        BigDecimal deducted = deduction == null
                ? BigDecimal.ZERO
                : deduction.payment().getAmount();

        return new PayoutResponse(
                payout.getId(),
                payout.getCycle().getId(),
                payout.getCycle().getSequenceNumber(),
                payout.getRecipient().getId(),
                payout.getRecipient().getName(),
                payout.getAmountPaid(),
                deducted,
                payout.getAmountPaid().subtract(deducted),
                deduction == null
                        ? List.of()
                        : deduction.allocations().stream().map(AllocationResponse::from).toList(),
                payout.getCreatedAt());
    }
}

package com.stokvel.dto;

import com.stokvel.model.Payment;
import com.stokvel.service.PaymentService.RecordedPayment;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * The payment, and every slice of it. The allocations are on the wire deliberately:
 * "R500 received" and "R300 of it settled what you owed Sarah, R200 reached the
 * March pot" are different statements, and the second is the one that makes the
 * rules visible. A client that only saw the total would have to guess at the split,
 * or recompute it — and recomputing authoritative values on the client is how two
 * versions of the truth start.
 *
 * deductedFromPayoutId is non-null only for the system-generated arrears deduction
 * (Rule 6), which is how the ledger tells "John paid" apart from "R200 was taken out
 * of John's payout".
 */
public record PaymentResponse(Long id,
                              Long memberId,
                              String memberName,
                              BigDecimal amount,
                              Instant createdAt,
                              Long deductedFromPayoutId,
                              List<AllocationResponse> allocations) {

    public static PaymentResponse from(RecordedPayment recorded) {
        Payment payment = recorded.payment();
        return new PaymentResponse(
                payment.getId(),
                payment.getMember().getId(),
                payment.getMember().getName(),
                payment.getAmount(),
                payment.getCreatedAt(),
                payment.getPayout() == null ? null : payment.getPayout().getId(),
                recorded.allocations().stream().map(AllocationResponse::from).toList());
    }
}

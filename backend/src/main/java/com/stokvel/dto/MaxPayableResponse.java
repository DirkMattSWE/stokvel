package com.stokvel.dto;

import com.stokvel.service.PaymentService.MaxPayable;

import java.math.BigDecimal;

/**
 * The most a member can pay right now, and the two halves it is made of. The total
 * is derived server-side and sent as an answer — it is the same figure recordPayment
 * refuses above, so the input cap and the refusal cannot disagree.
 */
public record MaxPayableResponse(Long memberId,
                                 BigDecimal arrears,
                                 BigDecimal contributionDue,
                                 BigDecimal maxPayable) {

    public static MaxPayableResponse from(Long memberId, MaxPayable max) {
        return new MaxPayableResponse(memberId, max.arrears(), max.contributionDue(), max.total());
    }
}

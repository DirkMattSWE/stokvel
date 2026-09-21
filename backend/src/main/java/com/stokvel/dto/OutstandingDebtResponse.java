package com.stokvel.dto;

import com.stokvel.model.Debt;
import com.stokvel.service.ArrearsService.OutstandingDebt;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One debt, with both the amount it was written for and what is still owed on it.
 *
 * Both numbers, deliberately. The debt row is never decremented, so "R500, of which
 * R300 is still outstanding" is the honest statement — and it is the shape that makes
 * partial settlement visible instead of looking like a smaller debt. A client shown
 * only the outstanding figure could not tell a half-paid R500 from a fresh R200.
 *
 * creditorName rather than a nested member: Debt.creditor is a lazy @ManyToOne, and
 * the DTO is where that stops being the caller's problem.
 */
public record OutstandingDebtResponse(Long id,
                                      String creditorName,
                                      Long creditorId,
                                      Integer cycleSequenceNumber,
                                      BigDecimal amount,
                                      BigDecimal outstanding,
                                      Instant createdAt) {

    public static OutstandingDebtResponse from(OutstandingDebt owed) {
        Debt debt = owed.debt();
        return new OutstandingDebtResponse(
                debt.getId(),
                debt.getCreditor().getName(),
                debt.getCreditor().getId(),
                debt.getCycle().getSequenceNumber(),
                debt.getAmount(),
                owed.outstanding(),
                debt.getCreatedAt());
    }
}

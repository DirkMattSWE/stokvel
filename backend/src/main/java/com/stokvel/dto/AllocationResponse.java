package com.stokvel.dto;

import com.stokvel.model.Allocation;

import java.math.BigDecimal;

/**
 * Where one slice of a payment landed. Exactly one of the two destinations is
 * populated, mirroring the row itself — cycleSequenceNumber for money that reached
 * the pot, creditorName for money that settled a debt.
 *
 * destination is spelled out rather than left for the client to infer from which
 * fields are null. The server owns what a row means; a client working it out from
 * nulls is a second implementation of the rule waiting to disagree.
 */
public record AllocationResponse(Long id,
                                 BigDecimal amount,
                                 Destination destination,
                                 Integer cycleSequenceNumber,
                                 Long debtId,
                                 String creditorName) {

    public enum Destination {
        /** Into the open cycle's pot. */
        POT,
        /** Against an outstanding debt, oldest first (Rule 7). */
        DEBT
    }

    public static AllocationResponse from(Allocation allocation) {
        if (allocation.getCycle() != null) {
            return new AllocationResponse(
                    allocation.getId(),
                    allocation.getAmount(),
                    Destination.POT,
                    allocation.getCycle().getSequenceNumber(),
                    null,
                    null);
        }
        return new AllocationResponse(
                allocation.getId(),
                allocation.getAmount(),
                Destination.DEBT,
                null,
                allocation.getDebt().getId(),
                allocation.getDebt().getCreditor().getName());
    }
}

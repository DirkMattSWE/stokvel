package com.stokvel.dto;

import com.stokvel.model.Cycle;

import java.time.LocalDate;

/**
 * The recipient is flattened to id + name rather than nested: Cycle.recipient is a
 * lazy @ManyToOne, and a DTO is where that stops being the caller's problem.
 */
public record CycleResponse(Long id,
                            Integer sequenceNumber,
                            Integer rotationNumber,
                            LocalDate dueDate,
                            Long recipientId,
                            String recipientName) {

    public static CycleResponse from(Cycle cycle) {
        return new CycleResponse(
                cycle.getId(),
                cycle.getSequenceNumber(),
                cycle.getRotationNumber(),
                cycle.getDueDate(),
                cycle.getRecipient().getId(),
                cycle.getRecipient().getName());
    }
}

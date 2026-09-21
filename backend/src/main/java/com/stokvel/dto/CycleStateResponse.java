package com.stokvel.dto;

import com.stokvel.service.CycleService.CycleState;

import java.math.BigDecimal;

/**
 * A round and both sides of its pot — "R1,500 of R2,000".
 *
 * The cycle itself is the existing CycleResponse rather than six fields copied out
 * again. That one is already the flattened, lazy-proxy-free shape, and one DTO
 * describing a cycle means one place to change when a cycle gains a field.
 *
 * shortfall is sent rather than left for the client to subtract. The subtraction is
 * trivial; the floor at zero is not obvious, and a client doing it itself would be a
 * second implementation of a rule that lives on the server.
 */
public record CycleStateResponse(CycleResponse cycle,
                                 BigDecimal collected,
                                 BigDecimal target,
                                 BigDecimal shortfall) {

    public static CycleStateResponse from(CycleState state) {
        return new CycleStateResponse(
                CycleResponse.from(state.cycle()),
                state.collected(),
                state.target(),
                state.shortfall());
    }
}

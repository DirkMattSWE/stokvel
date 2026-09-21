package com.stokvel.controller;

import com.stokvel.dto.CycleStateResponse;
import com.stokvel.service.CycleService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only, and that is the whole shape of it — CycleService never writes.
 *
 * There is no POST here for the same reason there is no payout endpoint: a cycle row
 * is written by addMember appending to the current rotation, or by the payout that
 * closes one (Rule 9). A way to create one by request would be a second author of
 * the rotation, and sequence_number's no-drift-path guarantee depends on there being
 * exactly one.
 *
 * The shortfalls endpoint lives on ArrearsController even though its path is
 * /api/cycles/{id}/shortfalls. It is an arrears question that happens to be keyed by
 * a cycle — who is behind, and by how much — rather than a fact about the round.
 */
@RestController
@RequestMapping("/api/cycles")
public class CycleController {

    private final CycleService cycleService;

    public CycleController(CycleService cycleService) {
        this.cycleService = cycleService;
    }

    /** Every round in sequence order, each with what its pot holds against its target. */
    @GetMapping
    public List<CycleStateResponse> cycles() {
        return cycleService.cycles().stream().map(CycleStateResponse::from).toList();
    }
}

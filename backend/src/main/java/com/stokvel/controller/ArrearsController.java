package com.stokvel.controller;

import com.stokvel.dto.CycleShortfallResponse;
import com.stokvel.dto.MemberArrearsResponse;
import com.stokvel.service.ArrearsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only, and that is the whole shape of it. ArrearsService never writes, so there
 * is no POST here to add — a debt row is written by a payout, never by a request.
 *
 * Both answers are derived on every call rather than read from a stored total, which
 * is why there is nothing to invalidate and no refresh endpoint.
 */
@RestController
@RequestMapping("/api")
public class ArrearsController {

    private final ArrearsService arrearsService;

    public ArrearsController(ArrearsService arrearsService) {
        this.arrearsService = arrearsService;
    }

    /** What one member owes, and to whom — oldest debt first, settled ones dropped. */
    @GetMapping("/members/{memberId}/arrears")
    public MemberArrearsResponse arrearsFor(@PathVariable Long memberId) {
        return MemberArrearsResponse.from(arrearsService.arrearsFor(memberId));
    }

    /**
     * Who is behind on one cycle — expected against paid, per member.
     *
     * Answerable for any cycle, not only the open one. On a cycle that has paid out
     * it is the record of who was short when the payout fired; on the open cycle it
     * is who still has time to fix it. Same query, and the difference is the reader's,
     * not the server's.
     */
    @GetMapping("/cycles/{cycleId}/shortfalls")
    public List<CycleShortfallResponse> shortfallsFor(@PathVariable Long cycleId) {
        return arrearsService.shortfallsFor(cycleId).stream()
                .map(CycleShortfallResponse::from)
                .toList();
    }
}

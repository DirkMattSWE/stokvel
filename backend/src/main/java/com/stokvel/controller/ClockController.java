package com.stokvel.controller;

import com.stokvel.dto.AdvanceClockRequest;
import com.stokvel.dto.AdvanceClockResponse;
import com.stokvel.dto.StokvelConfigResponse;
import com.stokvel.service.ClockService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin by design: unpacks the request, calls the service, maps to a DTO. No rule is
 * decided here — in particular, nothing in this class knows what makes a cycle due.
 *
 * There is deliberately no endpoint that fires a payout. A POST /api/payouts would
 * be a second trigger that bypasses the due date, and Rule 1 has exactly one: the
 * date arriving. It would also make the demo line false — the button below moves
 * time, and the payouts come back in the response because they fired on their own.
 */
@RestController
@RequestMapping("/api/clock")
public class ClockController {

    private final ClockService clockService;

    public ClockController(ClockService clockService) {
        this.clockService = clockService;
    }

    /** Where the clock stands. The UI's date display reads this, never the browser's. */
    @GetMapping
    public StokvelConfigResponse currentDate() {
        return StokvelConfigResponse.from(clockService.currentConfig());
    }

    /**
     * Moves the clock and reports what that made due.
     *
     * A refusal — moving backwards — surfaces as 400 through ApiExceptionHandler,
     * which maps IllegalArgumentException. Nothing is caught here.
     */
    @PostMapping("/advance")
    public AdvanceClockResponse advance(@RequestBody AdvanceClockRequest request) {
        return AdvanceClockResponse.of(
                request.targetDate(),
                clockService.advanceClock(request.targetDate()));
    }
}

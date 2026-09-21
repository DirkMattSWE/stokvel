package com.stokvel.dto;

import com.stokvel.service.PayoutService.PayoutOutcome;

import java.time.LocalDate;
import java.util.List;

/**
 * What moving the clock did: where the date now stands, and the payouts that fired
 * as a consequence, oldest first.
 *
 * Two fields rather than a bare list, because the date is the thing the button
 * actually changed. The payouts are a *consequence* — "this button doesn't pay
 * anyone, it moves time; the payout fires on its own" — and a response shaped as
 * "here are your payouts" would quietly tell the opposite story.
 *
 * The list is usually empty. Advancing a week inside a month changes nothing, and
 * that is the normal case, not a failure.
 */
public record AdvanceClockResponse(LocalDate currentDate, List<PayoutResponse> payouts) {

    public static AdvanceClockResponse of(LocalDate currentDate, List<PayoutOutcome> fired) {
        return new AdvanceClockResponse(
                currentDate,
                fired.stream()
                        .map(outcome -> PayoutResponse.from(outcome.payout(), outcome.deduction()))
                        .toList());
    }
}

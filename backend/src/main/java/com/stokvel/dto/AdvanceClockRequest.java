package com.stokvel.dto;

import java.time.LocalDate;

/**
 * Where to move the clock to (Rule 8).
 *
 * A target date, not a number of months. The client says where on the calendar it
 * wants to be; working out which cycles that makes due is the server's job, and the
 * cycle rows already carry their own due dates.
 */
public record AdvanceClockRequest(LocalDate targetDate) {
}

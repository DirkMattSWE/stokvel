package com.stokvel.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the client sends to create the stokvel.
 *
 * startDate is part of the request, not read from the server's clock: the caller
 * decides what "today" is (Rule 8).
 */
public record CreateStokvelRequest(BigDecimal contributionAmount,
                                   LocalDate startDate,
                                   Integer rotationCount) {
}

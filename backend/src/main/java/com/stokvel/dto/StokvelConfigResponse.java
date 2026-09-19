package com.stokvel.dto;

import com.stokvel.model.StokvelConfig;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The config as it crosses the wire. The entity itself never does.
 *
 * id is not carried: it is always 1, and a client that could see it might think
 * it had a choice about it.
 */
public record StokvelConfigResponse(BigDecimal contributionAmount,
                                    LocalDate currentDate,
                                    Integer rotationCount) {

    public static StokvelConfigResponse from(StokvelConfig config) {
        return new StokvelConfigResponse(
                config.getContributionAmount(),
                config.getCurrentDate(),
                config.getRotationCount());
    }
}

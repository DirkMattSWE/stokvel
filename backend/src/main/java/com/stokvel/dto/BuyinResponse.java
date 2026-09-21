package com.stokvel.dto;

import com.stokvel.service.BuyinService.BuyinRecorded;

import java.math.BigDecimal;
import java.util.List;

/**
 * What a joiner paid to join a rotation already under way, and who it reached
 * (Rule 4).
 *
 * Both halves, not just the total: "R1,000" on its own invites the question the
 * distributions answer, and the members named here are the evidence that buy-in
 * money went to people rather than into the current pot.
 */
public record BuyinResponse(BigDecimal amount, List<BuyinDistributionResponse> distributions) {

    public static BuyinResponse from(BuyinRecorded recorded) {
        return new BuyinResponse(
                recorded.buyin().getAmount(),
                recorded.distributions().stream().map(BuyinDistributionResponse::from).toList());
    }
}

package com.stokvel.dto;

import com.stokvel.model.BuyinDistribution;

import java.math.BigDecimal;

/**
 * One slice of a buy-in, named by the member it compensated.
 *
 * The recipient crosses as a name rather than the entity: BuyinDistribution holds a
 * lazy @ManyToOne to Member, and serialising the entity is how a lazy-loading
 * failure or a Jackson cycle gets into a response.
 */
public record BuyinDistributionResponse(Long recipientId, String recipient, BigDecimal amount) {

    public static BuyinDistributionResponse from(BuyinDistribution distribution) {
        return new BuyinDistributionResponse(
                distribution.getRecipient().getId(),
                distribution.getRecipient().getName(),
                distribution.getAmount());
    }
}

package com.stokvel.dto;

import com.stokvel.service.ArrearsService.CycleShortfall;

import java.math.BigDecimal;

/**
 * One member's standing on one cycle: what was expected, what reached the pot, and
 * the gap.
 *
 * All three, so the screen can say "Alice paid R300 of R500, R200 short" without the
 * client knowing what a contribution is. shortfall is floored at zero — a member who
 * overpaid is not owed money by the stokvel.
 *
 * paid counts allocations to this cycle, not payments made. A member who handed over
 * R500 while carrying arrears shows here as having paid whatever survived Rule 7,
 * which is the number that actually reached the recipient.
 *
 * The cycle's own recipient appears in this list too. They cannot owe themselves, so
 * no debt row exists for their shortfall — but it is why their pot came up light, and
 * hiding it would make the arithmetic on screen look wrong.
 */
public record CycleShortfallResponse(Long memberId,
                                     String memberName,
                                     BigDecimal expected,
                                     BigDecimal paid,
                                     BigDecimal shortfall) {

    public static CycleShortfallResponse from(CycleShortfall shortfall) {
        return new CycleShortfallResponse(
                shortfall.member().getId(),
                shortfall.member().getName(),
                shortfall.expected(),
                shortfall.paid(),
                shortfall.shortfall());
    }
}

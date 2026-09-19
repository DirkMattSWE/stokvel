package com.stokvel.dto;

import java.math.BigDecimal;

/**
 * Who paid, and how much. Nothing else — in particular no cycle: which cycle a
 * payment counts toward is the server's answer, not the client's, and no date: a
 * payment happens on the simulated clock's today (Rule 8).
 */
public record RecordPaymentRequest(Long memberId, BigDecimal amount) {
}

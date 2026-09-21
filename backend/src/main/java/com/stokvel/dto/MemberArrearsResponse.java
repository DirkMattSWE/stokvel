package com.stokvel.dto;

import com.stokvel.service.ArrearsService.MemberArrears;

import java.math.BigDecimal;
import java.util.List;

/**
 * What one member owes: the total, and every unsettled debt behind it.
 *
 * The total is on the wire even though the client could add the list up, because the
 * server is what decides which debts count — fully settled ones are dropped rather
 * than returned as zero. A client summing the list would get the same answer today
 * and a different one the moment that rule changed. Derived here, sent as an answer,
 * never recomputed on the other side.
 *
 * An empty list with a zero total is a real, complete answer: this member is square.
 */
public record MemberArrearsResponse(Long memberId,
                                    String memberName,
                                    BigDecimal totalOutstanding,
                                    List<OutstandingDebtResponse> debts) {

    public static MemberArrearsResponse from(MemberArrears arrears) {
        return new MemberArrearsResponse(
                arrears.member().getId(),
                arrears.member().getName(),
                arrears.total(),
                arrears.debts().stream().map(OutstandingDebtResponse::from).toList());
    }
}

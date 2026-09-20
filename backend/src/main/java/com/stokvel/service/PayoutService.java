package com.stokvel.service;

import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.DebtRepository;
import com.stokvel.repository.MemberRepository;
import com.stokvel.repository.StokvelConfigRepository;
import org.springframework.stereotype.Service;

/**
 * Fires a payout: counts the pot, records who was short, pays the recipient gross,
 * deducts what they owe, and continues the rotation if that cycle closed it.
 * Rules 1, 2, 6 and 9 all land here, in that order.
 *
 * It never asks whether a cycle is due. {@link ClockService} decided that by calling
 * findDueCycles() and is handing over a cycle that already is. Re-checking the date
 * would put a second reader of current_date in the system (Rule 8), and — worse —
 * give this method a way to decline. Rule 1 is "fires on the due date, regardless":
 * a method that can say no is a method someone can later teach to wait for a full pot.
 *
 * Built in three passes: 3a writes the debt rows (Rule 2), 3b pays out and deducts
 * (Rules 1 and 6), 3c continues the rotation (Rule 9).
 */
@Service
public class PayoutService {

    private final StokvelConfigRepository configRepository;
    private final MemberRepository memberRepository;
    private final AllocationRepository allocationRepository;
    private final DebtRepository debtRepository;

    public PayoutService(StokvelConfigRepository configRepository,
                         MemberRepository memberRepository,
                         AllocationRepository allocationRepository,
                         DebtRepository debtRepository) {
        this.configRepository = configRepository;
        this.memberRepository = memberRepository;
        this.allocationRepository = allocationRepository;
        this.debtRepository = debtRepository;
    }
}

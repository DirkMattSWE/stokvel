package com.stokvel.service;

import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import com.stokvel.repository.AllocationRepository;
import com.stokvel.repository.DebtRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Answers what is owed. Reads only — it never writes a debt row and never writes an
 * allocation, which is what keeps "where money lands" a question with exactly one
 * owner (PaymentService). PayoutService writes the debt rows; this service is where
 * everyone comes to ask what they add up to.
 *
 * Nothing here is stored. A debt's amount is never decremented and carries no status
 * flag, so what is outstanding is a subtraction done at read time: the debt's amount
 * minus everything allocated against it. A flag can disagree with the allocation
 * rows; this subtraction cannot, and partial settlement falls out of it for free.
 */
@Service
public class ArrearsService {

    private final DebtRepository debtRepository;
    private final AllocationRepository allocationRepository;

    public ArrearsService(DebtRepository debtRepository, AllocationRepository allocationRepository) {
        this.debtRepository = debtRepository;
        this.allocationRepository = allocationRepository;
    }

    /** One debt and what is still owed on it — the debt row itself is unchanged. */
    public record OutstandingDebt(Debt debt, BigDecimal outstanding) {
    }

    /**
     * What this member still owes, oldest debt first, fully settled debts left out.
     *
     * The order is the order Rule 7 settles in, so it is decided here rather than by
     * the caller. Fully settled debts are dropped rather than returned as zero: an
     * allocation of zero is not a fact worth recording, and the schema's
     * CHECK (amount > 0) would reject the row anyway.
     *
     * One sum query per debt. That is N+1 in shape, but N here is the number of
     * times one member has missed a contribution — single digits in any stokvel
     * that still exists — and the alternative is a correlated subquery that is
     * harder to read than the rule it implements.
     */
    @Transactional(readOnly = true)
    public List<OutstandingDebt> outstandingFor(Member debtor) {
        List<OutstandingDebt> owed = new ArrayList<>();
        for (Debt debt : debtRepository.findAllByDebtorOrderByCreatedAtAscIdAsc(debtor)) {
            BigDecimal outstanding = debt.getAmount().subtract(allocationRepository.sumByDebtId(debt.getId()));
            if (outstanding.signum() > 0) {
                owed.add(new OutstandingDebt(debt, outstanding));
            }
        }
        return owed;
    }

    /**
     * The member's total arrears — what Rule 6 deducts from their payout when their
     * turn comes, and what the ledger shows next to their name.
     */
    @Transactional(readOnly = true)
    public BigDecimal totalOutstandingFor(Member debtor) {
        return outstandingFor(debtor).stream()
                .map(OutstandingDebt::outstanding)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}

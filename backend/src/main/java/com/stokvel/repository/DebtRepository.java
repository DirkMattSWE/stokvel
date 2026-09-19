package com.stokvel.repository;

import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DebtRepository extends JpaRepository<Debt, Long> {

    List<Debt> findAllByOrderByCreatedAtAsc();

    /**
     * One member's debts, oldest first — the order Rule 7 settles them in.
     *
     * The id tie-break is load-bearing, for the same reason it is on the member
     * query: created_at comes from the simulated clock, so every debt written by
     * one payout shares a timestamp to the day. Without the tie-break, "oldest
     * first" would be whatever order SQLite happened to return, and a member's
     * settlement history would not be reproducible.
     */
    List<Debt> findAllByDebtorOrderByCreatedAtAscIdAsc(Member debtor);
}

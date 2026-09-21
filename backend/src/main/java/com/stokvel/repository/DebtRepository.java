package com.stokvel.repository;

import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
     *
     * Creditor and cycle are fetch-joined because open-in-view is off: by the time a
     * response is being written the transaction is closed, and a DTO reaching for
     * debt.getCreditor().getName() would fail on a lazy proxy. Plain JOIN FETCH, not
     * LEFT — both columns are NOT NULL, so there is no row for an inner join to drop.
     * Both hops are @ManyToOne, which is the case where fetch-joining is safe.
     */
    @Query("SELECT d FROM Debt d "
            + "JOIN FETCH d.creditor "
            + "JOIN FETCH d.cycle "
            + "WHERE d.debtor = :debtor "
            + "ORDER BY d.createdAt ASC, d.id ASC")
    List<Debt> findAllByDebtorOrderByCreatedAtAscIdAsc(@Param("debtor") Member debtor);
}

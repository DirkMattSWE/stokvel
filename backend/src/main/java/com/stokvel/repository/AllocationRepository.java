package com.stokvel.repository;

import com.stokvel.model.Allocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;

public interface AllocationRepository extends JpaRepository<Allocation, Long> {

    /**
     * The pot for a cycle. Allocations that settled debt have cycle_id null, so
     * they are excluded automatically — only the remainder that reached the pot
     * is counted (Rule 7).
     */
    @Query("SELECT COALESCE(SUM(a.amount), 0.00) FROM Allocation a WHERE a.cycle.id = :cycleId")
    BigDecimal sumByCycleId(@Param("cycleId") Long cycleId);

    /**
     * How much has been settled against one debt. Outstanding is
     * debt.amount minus this — the debt row itself is never decremented.
     */
    @Query("SELECT COALESCE(SUM(a.amount), 0.00) FROM Allocation a WHERE a.debt.id = :debtId")
    BigDecimal sumByDebtId(@Param("debtId") Long debtId);

    /**
     * Every slice of one payment, with debt and creditor pulled in the same query
     * so the ledger can name who was paid ("settles debt to Sarah, Thabo").
     *
     * LEFT, not inner: allocations to a cycle have debt null, and an inner join
     * would silently drop them.
     */
    @Query("SELECT a FROM Allocation a "
            + "LEFT JOIN FETCH a.debt d "
            + "LEFT JOIN FETCH d.creditor "
            + "WHERE a.payment.id = :paymentId")
    List<Allocation> findByPaymentId(@Param("paymentId") Long paymentId);
}

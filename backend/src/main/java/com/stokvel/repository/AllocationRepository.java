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
     * What one member actually contributed to one cycle's pot — their side of the
     * Rule 2 comparison.
     *
     * Summed over allocations rather than payments, which is the whole reason those
     * are two tables: a payment row has no cycle_id. A member who hands over R500
     * while carrying R400 of arrears lands only R100 here, because Rule 7 sent the
     * rest to their old debts. Summing payment.amount would call them paid up for a
     * cycle whose pot never saw the money.
     */
    @Query("SELECT COALESCE(SUM(a.amount), 0.00) FROM Allocation a "
            + "WHERE a.cycle.id = :cycleId AND a.payment.member.id = :memberId")
    BigDecimal sumByCycleIdAndMemberId(@Param("cycleId") Long cycleId,
                                       @Param("memberId") Long memberId);

    /**
     * Every slice of one payment, with cycle, debt and creditor pulled in the same
     * query so the ledger can name where each slice went ("R200 to the March pot,
     * R300 settling debt to Sarah").
     *
     * LEFT, not inner, on every hop: an allocation has exactly one of cycle or debt,
     * so an inner join on either would silently drop the other half of the rows.
     * The FETCH half is what keeps DTO mapping working with open-in-view off — by
     * the time a response is written there is nothing left to lazy-load.
     */
    @Query("SELECT a FROM Allocation a "
            + "LEFT JOIN FETCH a.cycle "
            + "LEFT JOIN FETCH a.debt d "
            + "LEFT JOIN FETCH d.creditor "
            + "WHERE a.payment.id = :paymentId "
            + "ORDER BY a.id ASC")
    List<Allocation> findByPaymentId(@Param("paymentId") Long paymentId);
}

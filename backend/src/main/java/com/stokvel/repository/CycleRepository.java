package com.stokvel.repository;

import com.stokvel.model.Cycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface CycleRepository extends JpaRepository<Cycle, Long> {

    List<Cycle> findAllByOrderBySequenceNumberAsc();

    /**
     * The cycle whose pot is still open — the first one that has not paid out yet.
     * Payments allocate here.
     *
     * Takes no date: the payout row's existence is what closes a cycle, so the
     * handover is instant at payout time and Rule 8 cannot be violated here.
     * Empty once every cycle has paid out (rotation complete).
     */
    @Query("SELECT c FROM Cycle c "
            + "WHERE NOT EXISTS (SELECT p FROM Payout p WHERE p.cycle = c) "
            + "ORDER BY c.dueDate ASC "
            + "LIMIT 1")
    Optional<Cycle> findOpenCycle();

    /**
     * Cycles that have come due and not yet paid out, oldest first. Plural because
     * advancing the clock by several months makes several cycles due at once, and
     * each payout changes the debt picture the next one sees — so PayoutService
     * fires them in this order.
     */
    @Query("SELECT c FROM Cycle c "
            + "WHERE c.dueDate <= :currentDate "
            + "AND NOT EXISTS (SELECT p FROM Payout p WHERE p.cycle = c) "
            + "ORDER BY c.dueDate ASC")
    List<Cycle> findDueCycles(@Param("currentDate") LocalDate currentDate);
}

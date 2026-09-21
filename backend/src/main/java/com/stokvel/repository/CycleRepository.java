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
     * The tail of the rotation — the most recently appended cycle. New cycles chain
     * their due_date off this one rather than off config.current_date, because
     * current_date moves as the clock advances (Rule 8) and a member joining in
     * month three would otherwise be given a due date computed from month three.
     *
     * Ordered by sequence_number rather than due_date: it is UNIQUE and strictly
     * monotonic, so there is no tie for "last" even in principle.
     */
    Optional<Cycle> findTopByOrderBySequenceNumberDesc();

    /**
     * The cycle immediately before this one, by sequence number — which is the cycle
     * whose due date is when this one's month began (Rule 3's boundary).
     *
     * Safe to reach for with {@code sequenceNumber - 1} rather than an ORDER BY:
     * sequence_number is UNIQUE, assigned as tail + 1, and cycles are never
     * reordered or deleted, so there are no gaps for the arithmetic to fall into.
     * Empty for the first cycle of the stokvel, which has no month before it.
     */
    Optional<Cycle> findBySequenceNumber(Integer sequenceNumber);

    /**
     * Cycles with their recipient already loaded, for read endpoints that name the
     * member. Plain JOIN FETCH, not LEFT: recipient_id is NOT NULL, so there is no
     * row for an inner join to silently drop.
     */
    @Query("SELECT c FROM Cycle c JOIN FETCH c.recipient ORDER BY c.sequenceNumber ASC")
    List<Cycle> findAllWithRecipient();

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

package com.stokvel.repository;

import com.stokvel.model.Cycle;
import com.stokvel.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findByCycle(Cycle cycle);

    /**
     * Every payout for the ledger, recipient and cycle loaded. Plain JOIN FETCH is
     * safe on both: recipient_id and cycle_id are NOT NULL, so there is no row for
     * an inner join to lose.
     */
    @Query("SELECT p FROM Payout p "
            + "JOIN FETCH p.recipient "
            + "JOIN FETCH p.cycle "
            + "ORDER BY p.createdAt ASC, p.id ASC")
    List<Payout> findAllForLedger();
}

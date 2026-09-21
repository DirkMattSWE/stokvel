package com.stokvel.repository;

import com.stokvel.model.Buyin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BuyinRepository extends JpaRepository<Buyin, Long> {

    /**
     * Every buy-in for the ledger, with the joiner loaded. Plain JOIN FETCH is safe:
     * member_id is NOT NULL, so there is no row for an inner join to lose.
     */
    @Query("SELECT b FROM Buyin b "
            + "JOIN FETCH b.member "
            + "ORDER BY b.createdAt ASC, b.id ASC")
    List<Buyin> findAllForLedger();
}

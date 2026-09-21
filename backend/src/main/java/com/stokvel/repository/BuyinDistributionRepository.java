package com.stokvel.repository;

import com.stokvel.model.BuyinDistribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface BuyinDistributionRepository extends JpaRepository<BuyinDistribution, Long> {

    /**
     * Every distribution, for the ledger to group under its buy-in. One query for
     * all of them rather than one per buy-in — the same reason
     * AllocationRepository.findAllForLedger() exists: the ledger renders every
     * buy-in there has ever been, so the per-buy-in version would be N+1 by
     * construction.
     *
     * The recipient is fetched because the ledger names them; the buy-in is not,
     * because grouping only reads its id, and the identifier of a lazy @ManyToOne
     * comes off the foreign key without loading the row.
     */
    @Query("SELECT d FROM BuyinDistribution d "
            + "JOIN FETCH d.recipient "
            + "ORDER BY d.id ASC")
    List<BuyinDistribution> findAllForLedger();
}

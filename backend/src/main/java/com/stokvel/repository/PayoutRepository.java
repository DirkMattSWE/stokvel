package com.stokvel.repository;

import com.stokvel.model.Cycle;
import com.stokvel.model.Payout;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PayoutRepository extends JpaRepository<Payout, Long> {

    Optional<Payout> findByCycle(Cycle cycle);
}

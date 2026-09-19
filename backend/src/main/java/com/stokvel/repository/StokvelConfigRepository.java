package com.stokvel.repository;

import com.stokvel.model.StokvelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Not in the original package layout — flagged as an open item until the code
 * that forces it existed. StokvelSetupService creates the singleton row and
 * ClockService mutates current_date (Rule 8); both need somewhere to save it.
 * Thin, as expected: one row, no custom queries.
 */
public interface StokvelConfigRepository extends JpaRepository<StokvelConfig, Long> {
}

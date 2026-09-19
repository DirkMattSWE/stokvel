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

    /** The singleton's id. schema.sql enforces it with CHECK (id = 1). */
    Long SINGLETON_ID = 1L;

    /**
     * The config, or a refusal. Every service that writes a timestamped row starts
     * here, so the alternative is each of them carrying its own copy of the same
     * findById(1L).orElseThrow — four chances to word the failure differently, or
     * to forget the check and get a NoSuchElementException instead.
     */
    default StokvelConfig require() {
        return findById(SINGLETON_ID).orElseThrow(() -> new IllegalStateException(
                "No stokvel exists yet. Create it before anything else."));
    }
}

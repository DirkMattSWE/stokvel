package com.stokvel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Singleton row (id = 1). No foreign keys in or out.
 * current_date is the single mutable value in the system (Rule 8).
 */
@Entity
@Table(name = "stokvel_config")
public class StokvelConfig {

    @Id
    private Long id;

    @Column(name = "contribution_amount", nullable = false)
    private BigDecimal contributionAmount;

    // Backtick-quoted deliberately: CURRENT_DATE is a SQLite literal keyword, and
    // an unquoted reference to this column resolves to the OS's today instead of
    // the stored value — silently. schema.sql quotes it for the same reason.
    @Column(name = "`current_date`", nullable = false)
    private LocalDate currentDate;

    /**
     * How many full rotations this stokvel runs. Fixed at creation and never
     * changed — the members already paid out must not be able to extend the
     * commitment of the members still waiting.
     */
    @Column(name = "rotation_count", nullable = false)
    private Integer rotationCount;

    protected StokvelConfig() {
        // JPA
    }

    public StokvelConfig(Long id, BigDecimal contributionAmount, LocalDate currentDate, Integer rotationCount) {
        this.id = id;
        this.contributionAmount = contributionAmount;
        this.currentDate = currentDate;
        this.rotationCount = rotationCount;
    }

    public Long getId() {
        return id;
    }

    public BigDecimal getContributionAmount() {
        return contributionAmount;
    }

    public LocalDate getCurrentDate() {
        return currentDate;
    }

    public Integer getRotationCount() {
        return rotationCount;
    }

    /**
     * The one mutable value in the system (Rule 8). Everything else here,
     * and every other entity in the model, is append-only.
     */
    public void setCurrentDate(LocalDate currentDate) {
        this.currentDate = currentDate;
    }
}

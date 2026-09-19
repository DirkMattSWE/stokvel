package com.stokvel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

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

    /**
     * The simulated clock as an instant, for stamping created_at on the rows the
     * rules write — members, payments, debts, payouts. Business logic never calls
     * Instant.now() (Rule 8), so this is the only source those timestamps have.
     *
     * It lives on the entity rather than in each service because it is a fact about
     * this column, not about any one caller: a service that reached for the wall
     * clock instead would make rotation order and debt settlement order depend on
     * the day the demo happens to be run.
     *
     * UTC, not the system zone, for the same reason date_class=text is on the JDBC
     * URL — a local-midnight conversion is how a stored date silently becomes the
     * day before somewhere else.
     */
    public Instant simulatedNow() {
        return currentDate.atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}

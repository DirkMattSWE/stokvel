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

    @Column(name = "current_date", nullable = false)
    private LocalDate currentDate;

    protected StokvelConfig() {
        // JPA
    }

    public StokvelConfig(Long id, BigDecimal contributionAmount, LocalDate currentDate) {
        this.id = id;
        this.contributionAmount = contributionAmount;
        this.currentDate = currentDate;
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

    /**
     * The one mutable value in the system (Rule 8). Everything else here,
     * and every other entity in the model, is append-only.
     */
    public void setCurrentDate(LocalDate currentDate) {
        this.currentDate = currentDate;
    }
}

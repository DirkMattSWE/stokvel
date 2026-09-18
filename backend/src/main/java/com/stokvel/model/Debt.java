package com.stokvel.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable record of a shortfall. Two FKs to member — an ordinary entity with two
 * roles, not a junction table. amount is never decremented.
 *
 * No status, no amount_remaining, no is_settled column. Outstanding balance is
 * derived: amount - SUM(allocation.amount WHERE debt_id = this debt). A flag can
 * disagree with the allocation rows; the subtraction cannot, and it handles partial
 * settlement for free.
 */
@Entity
@Table(name = "debt")
public class Debt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debtor_id", nullable = false)
    private Member debtor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "creditor_id", nullable = false)
    private Member creditor;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private Cycle cycle;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Debt() {
        // JPA
    }

    public Debt(Member debtor, Member creditor, Cycle cycle, BigDecimal amount, Instant createdAt) {
        this.debtor = debtor;
        this.creditor = creditor;
        this.cycle = cycle;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Member getDebtor() {
        return debtor;
    }

    public Member getCreditor() {
        return creditor;
    }

    public Cycle getCycle() {
        return cycle;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

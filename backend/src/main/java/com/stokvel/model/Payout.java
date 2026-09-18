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
 * A single event, not a range — the range belongs to the cycle. amount_paid is
 * gross, before arrears deduction (Rule 6). The deduction itself is a separate
 * Payment row (with payout_id set), not stored here — nothing allocates to a
 * payout, money only flows out.
 */
@Entity
@Table(name = "payout")
public class Payout {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id", nullable = false)
    private Cycle cycle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Member recipient;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payout() {
        // JPA
    }

    public Payout(Cycle cycle, Member recipient, BigDecimal amountPaid, Instant createdAt) {
        this.cycle = cycle;
        this.recipient = recipient;
        this.amountPaid = amountPaid;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Cycle getCycle() {
        return cycle;
    }

    public Member getRecipient() {
        return recipient;
    }

    public BigDecimal getAmountPaid() {
        return amountPaid;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

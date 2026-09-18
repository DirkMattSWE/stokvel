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
 * The raw fact that money arrived. No cycle_id — which cycle a payment counts
 * toward is the allocation's job, not the payment's. That separation is what makes
 * Rule 7 (debt-settles-first) a logic change instead of a schema rewrite.
 *
 * payout_id marks a payment as system-generated (an arrears deduction) rather than
 * voluntary — an explicit link, never inferred from timing.
 */
@Entity
@Table(name = "payment")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private BigDecimal amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payout_id")
    private Payout payout;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Payment() {
        // JPA
    }

    public Payment(Member member, BigDecimal amount, Payout payout, Instant createdAt) {
        this.member = member;
        this.amount = amount;
        this.payout = payout;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Member getMember() {
        return member;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Payout getPayout() {
        return payout;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

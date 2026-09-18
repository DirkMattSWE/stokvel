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
 * The raw fact that a joiner paid their buy-in gap. Same event-then-distribution
 * shape as payment/allocation — the split across already-paid members lives in
 * BuyinDistribution, since one buy-in can fan out across several recipients
 * proportionally to their gap (Rule 4). Buy-in money does not enter the current
 * cycle's pot, so this deliberately does not allocate into the payment/allocation
 * tables.
 */
@Entity
@Table(name = "buyin")
public class Buyin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "member_id", nullable = false)
    private Member member;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Buyin() {
        // JPA
    }

    public Buyin(Member member, BigDecimal amount, Instant createdAt) {
        this.member = member;
        this.amount = amount;
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

    public Instant getCreatedAt() {
        return createdAt;
    }
}

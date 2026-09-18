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

/**
 * One slice of a buy-in, landing with one already-paid member. Mirrors
 * allocation's relationship to payment: one buy-in produces one row per
 * recipient, split proportionally to each recipient's gap (Rule 4).
 */
@Entity
@Table(name = "buyin_distribution")
public class BuyinDistribution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "buyin_id", nullable = false)
    private Buyin buyin;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Member recipient;

    @Column(nullable = false)
    private BigDecimal amount;

    protected BuyinDistribution() {
        // JPA
    }

    public BuyinDistribution(Buyin buyin, Member recipient, BigDecimal amount) {
        this.buyin = buyin;
        this.recipient = recipient;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Buyin getBuyin() {
        return buyin;
    }

    public Member getRecipient() {
        return recipient;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}

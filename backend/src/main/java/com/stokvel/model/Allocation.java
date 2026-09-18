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
 * Where a slice of a payment landed. Exactly one of cycle/debt must be set — that
 * rule is enforced in the service layer (AllocationRepository/PaymentService), not
 * here; the entity itself allows either combination because a CHECK constraint is
 * optional belt-and-braces per spec, not a hard requirement.
 *
 * An allocation is a slice of one payment, not a monthly total — one payment
 * produces one row per destination.
 */
@Entity
@Table(name = "allocation")
public class Allocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payment_id", nullable = false)
    private Payment payment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cycle_id")
    private Cycle cycle;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "debt_id")
    private Debt debt;

    @Column(nullable = false)
    private BigDecimal amount;

    protected Allocation() {
        // JPA
    }

    public Allocation(Payment payment, Cycle cycle, Debt debt, BigDecimal amount) {
        this.payment = payment;
        this.cycle = cycle;
        this.debt = debt;
        this.amount = amount;
    }

    public Long getId() {
        return id;
    }

    public Payment getPayment() {
        return payment;
    }

    public Cycle getCycle() {
        return cycle;
    }

    public Debt getDebt() {
        return debt;
    }

    public BigDecimal getAmount() {
        return amount;
    }
}

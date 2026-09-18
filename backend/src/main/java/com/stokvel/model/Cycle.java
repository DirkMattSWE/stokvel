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

import java.time.LocalDate;

/**
 * No start date — a cycle's start is the previous cycle's due_date.
 * sequence_number is derivable from date order but kept deliberately: cycles are
 * never reordered or deleted, so it has no drift path (the one exception to
 * derive-everything). recipient_id is fixed at creation, never reordered (Rule 5).
 */
@Entity
@Table(name = "cycle")
public class Cycle {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sequence_number", nullable = false)
    private Integer sequenceNumber;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private Member recipient;

    protected Cycle() {
        // JPA
    }

    public Cycle(Integer sequenceNumber, LocalDate dueDate, Member recipient) {
        this.sequenceNumber = sequenceNumber;
        this.dueDate = dueDate;
        this.recipient = recipient;
    }

    public Long getId() {
        return id;
    }

    public Integer getSequenceNumber() {
        return sequenceNumber;
    }

    public LocalDate getDueDate() {
        return dueDate;
    }

    public Member getRecipient() {
        return recipient;
    }
}

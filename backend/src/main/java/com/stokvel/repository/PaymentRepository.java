package com.stokvel.repository;

import com.stokvel.model.Member;
import com.stokvel.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    List<Payment> findAllByOrderByCreatedAtAsc();

    List<Payment> findAllByMemberOrderByCreatedAtAsc(Member member);

    /**
     * The auto-generated arrears deduction for a payout, if there was one.
     * Its amount is what the ledger shows deducted from the gross payout.
     */
    Optional<Payment> findByPayoutId(Long payoutId);

    /**
     * Every payment for the ledger, with the member named and the payout it came out
     * of already loaded.
     *
     * LEFT JOIN FETCH on payout, never plain JOIN: payout_id is null on every
     * voluntary payment, and an inner join would silently drop exactly the rows the
     * ledger is mostly made of. The fetch is what stops the ledger walking back to
     * the database once per row to ask whether a payment was an auto-deduction.
     */
    @Query("SELECT p FROM Payment p "
            + "JOIN FETCH p.member "
            + "LEFT JOIN FETCH p.payout "
            + "ORDER BY p.createdAt ASC, p.id ASC")
    List<Payment> findAllForLedger();
}

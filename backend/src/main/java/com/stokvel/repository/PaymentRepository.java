package com.stokvel.repository;

import com.stokvel.model.Member;
import com.stokvel.model.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

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
}

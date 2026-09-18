package com.stokvel.repository;

import com.stokvel.model.Debt;
import com.stokvel.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DebtRepository extends JpaRepository<Debt, Long> {

    List<Debt> findAllByOrderByCreatedAtAsc();

    List<Debt> findAllByDebtorOrderByCreatedAtAsc(Member debtor);
}

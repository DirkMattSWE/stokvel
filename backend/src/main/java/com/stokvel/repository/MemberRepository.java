package com.stokvel.repository;

import com.stokvel.model.Member;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MemberRepository extends JpaRepository<Member, Long> {

    /**
     * Rotation order (Rule 5). The id tie-break matters: created_at is taken from
     * the simulated clock, not the wall clock, so every member added on the same
     * simulated day shares a timestamp — which is exactly what happens when the
     * founding members are added during setup. id is append-only and monotonic,
     * so it settles the order without inventing a position column.
     */
    List<Member> findAllByOrderByCreatedAtAscIdAsc();
}

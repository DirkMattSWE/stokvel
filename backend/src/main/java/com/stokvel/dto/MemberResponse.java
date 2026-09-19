package com.stokvel.dto;

import com.stokvel.model.Member;

import java.time.Instant;

/**
 * createdAt is on the wire deliberately: it is the member's rotation position, and
 * it comes from the simulated clock, so it is the value a reader needs to make
 * sense of who joined before which cycle.
 */
public record MemberResponse(Long id, String name, Instant createdAt) {

    public static MemberResponse from(Member member) {
        return new MemberResponse(member.getId(), member.getName(), member.getCreatedAt());
    }
}

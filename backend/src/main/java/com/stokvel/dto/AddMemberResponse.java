package com.stokvel.dto;

import com.stokvel.service.StokvelSetupService.MemberAdded;

/**
 * Both rows the call wrote. Returning the cycle as well as the member is what makes
 * the invariant visible from a single request: adding a member grew the rotation,
 * and here is the turn they were given.
 */
public record AddMemberResponse(MemberResponse member, CycleResponse cycle) {

    public static AddMemberResponse from(MemberAdded added) {
        return new AddMemberResponse(
                MemberResponse.from(added.member()),
                CycleResponse.from(added.cycle()));
    }
}

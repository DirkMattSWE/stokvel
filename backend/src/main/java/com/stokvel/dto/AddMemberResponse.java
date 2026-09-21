package com.stokvel.dto;

import com.stokvel.service.StokvelSetupService.MemberAdded;

/**
 * Every row the call wrote. Returning the cycle as well as the member is what makes
 * the invariant visible from a single request: adding a member grew the rotation,
 * and here is the turn they were given.
 *
 * buyin is null for a founding member, who joined a rotation that had not started
 * and so missed nothing (Rule 4). For anyone else it is the price of arriving late,
 * itemised by who it went to — and the client does not have to re-read the ledger to
 * show it.
 */
public record AddMemberResponse(MemberResponse member, CycleResponse cycle, BuyinResponse buyin) {

    public static AddMemberResponse from(MemberAdded added) {
        return new AddMemberResponse(
                MemberResponse.from(added.member()),
                CycleResponse.from(added.cycle()),
                added.buyin() == null ? null : BuyinResponse.from(added.buyin()));
    }
}

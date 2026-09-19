package com.stokvel.dto;

/** Only a name. Rotation position is creation order, never supplied by the client (Rule 5). */
public record AddMemberRequest(String name) {
}

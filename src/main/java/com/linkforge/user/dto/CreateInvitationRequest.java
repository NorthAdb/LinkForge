package com.linkforge.user.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class CreateInvitationRequest {

    /** ADMIN / MEMBER; absent means MEMBER. Validated in the service so an
     * unknown value is a business 400, not a binding error. */
    private String invitedRole;

    @Size(max = 64, message = "noteName must be at most 64 chars")
    private String noteName;
}

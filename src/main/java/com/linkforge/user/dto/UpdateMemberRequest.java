package com.linkforge.user.dto;

import lombok.Data;

@Data
public class UpdateMemberRequest {

    /** ADMIN / MEMBER; null leaves the role unchanged. */
    private String role;

    /** 1 = active, 0 = disabled; null leaves the status unchanged. Disabling
     * is a session kill (token_version bump + refresh revocation); enabling
     * only flips the flag back. */
    private Integer status;
}

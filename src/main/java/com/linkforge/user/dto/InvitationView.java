package com.linkforge.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/** Pending-invitation list row. Deliberately free of anything token-shaped. */
@Data
@AllArgsConstructor
public class InvitationView {
    private Long id;
    private String invitedRole;
    private String noteName;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime expiresAt;
}

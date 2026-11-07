package com.linkforge.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * The creation response is the ONLY place the raw invitation token ever
 * appears — the database stores its SHA-256 hash, and the pending list view
 * carries no token material at all.
 */
@Data
@AllArgsConstructor
public class InvitationCreatedResponse {
    private Long id;
    /** Raw invitation token (the credential itself); shown once. */
    private String token;
    /** Carrier string the creator hands to the invitee out of band. */
    private String invitationLink;
    private String invitedRole;
    private String noteName;
    private LocalDateTime expiresAt;
}

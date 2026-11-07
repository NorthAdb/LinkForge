package com.linkforge.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * The temporary password exists only in this response — it is stored as a
 * BCrypt hash and never logged. The member must replace it on first login
 * (must_change_password is set alongside it).
 */
@Data
@AllArgsConstructor
public class ResetPasswordResponse {
    private String temporaryPassword;
}

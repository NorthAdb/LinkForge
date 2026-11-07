package com.linkforge.user.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class LoginResponse {
    private String token;
    private Long userId;
    private Long tenantId;
    private String username;
    private String tenantName;
    private String tenantRole;
    /** Batch 2a: raw refresh token; only its SHA-256 hash is stored server-side. */
    private String refreshToken;
    /** True when an admin reset set the forced-change flag; the API hard-blocks
     * everything except change-password/logout/refresh until it is cleared. */
    private Boolean mustChangePassword;

    /** Pre-2a shape, kept so existing construction sites stay unchanged. */
    public LoginResponse(String token, Long userId, Long tenantId, String username,
                         String tenantName, String tenantRole) {
        this(token, userId, tenantId, username, tenantName, tenantRole, null, null);
    }
}

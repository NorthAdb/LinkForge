package com.linkforge.security;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthUser {
    private Long userId;
    private Long tenantId;
    private String username;
    /** "USER"/"TENANT" on key-authenticated requests; null for JWT users. */
    private String apiKeyScope;
}

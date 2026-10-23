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
}

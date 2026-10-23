package com.linkforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "tenantName required")
    private String tenantName;

    @NotBlank(message = "username required")
    private String username;

    @NotBlank(message = "password required")
    private String password;
}

package com.linkforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "tenantName required")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$",
            message = "tenantName must be 3-64 chars of letters, digits, underscore or hyphen")
    private String tenantName;

    @NotBlank(message = "username required")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$",
            message = "username must be 3-64 chars of letters, digits, underscore or hyphen")
    private String username;

    @NotBlank(message = "password required")
    @Size(min = 8, max = 64, message = "password must be 8-64 chars")
    private String password;
}

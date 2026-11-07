package com.linkforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChangePasswordRequest {

    @NotBlank(message = "currentPassword required")
    private String currentPassword;

    @NotBlank(message = "newPassword required")
    @Size(min = 8, max = 64, message = "password must be 8-64 chars")
    private String newPassword;
}

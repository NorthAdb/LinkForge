package com.linkforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LogoutRequest {

    @NotBlank(message = "refreshToken required")
    private String refreshToken;
}

package com.linkforge.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class AcceptInvitationRequest {

    @NotBlank(message = "token required")
    private String token;

    @NotBlank(message = "username required")
    @Pattern(regexp = "^[A-Za-z0-9_-]{3,64}$",
            message = "username must be 3-64 chars of letters, digits, underscore or hyphen")
    private String username;

    /** Length is checked in the service, after the invitation itself has been
     * validated, so a bad token and a short password do not trade places in
     * the response order (invitation failures stay one uniform 401). */
    @NotBlank(message = "password required")
    private String password;
}

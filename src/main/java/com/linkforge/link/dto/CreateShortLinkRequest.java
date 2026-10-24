package com.linkforge.link.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CreateShortLinkRequest {

    @NotBlank(message = "originalUrl required")
    @Size(max = 2048, message = "originalUrl too long")
    private String originalUrl;

    @Size(max = 128, message = "title too long")
    private String title;

    /** Optional user-chosen code: 4-16 chars of [A-Za-z0-9_-], not a reserved word.
     * Short codes are case-sensitive (V4, utf8mb4_0900_bin): AbC123 and abc123 are
     * two distinct links; reserved words are still rejected case-insensitively. */
    @Size(max = 16, message = "customCode too long")
    private String customCode;

    /** Optional; must be in the future when present. */
    private LocalDateTime expireAt;
}

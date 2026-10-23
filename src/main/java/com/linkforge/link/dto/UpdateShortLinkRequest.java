package com.linkforge.link.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateShortLinkRequest {

    @Size(max = 2048, message = "originalUrl too long")
    private String originalUrl;

    @Size(max = 128, message = "title too long")
    private String title;

    /** Optional; must be in the future when present. Cannot be cleared. */
    private LocalDateTime expireAt;
}

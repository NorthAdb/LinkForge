package com.linkforge.link.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /** B3: 4-64 chars when present; stored as a BCrypt hash, never returned. */
    @Size(max = 64, message = "accessPassword too long")
    private String accessPassword;

    /** C1 wave B (D-C1-6): merged into originalUrl at write time, null = absent. */
    @Pattern(regexp = "[A-Za-z0-9._-]*", message = "utmSource must be letters, digits, dot, underscore or hyphen")
    @Size(max = 64, message = "utmSource too long")
    private String utmSource;

    @Pattern(regexp = "[A-Za-z0-9._-]*", message = "utmMedium must be letters, digits, dot, underscore or hyphen")
    @Size(max = 64, message = "utmMedium too long")
    private String utmMedium;

    @Pattern(regexp = "[A-Za-z0-9._-]*", message = "utmCampaign must be letters, digits, dot, underscore or hyphen")
    @Size(max = 64, message = "utmCampaign too long")
    private String utmCampaign;

    @Pattern(regexp = "[A-Za-z0-9._-]*", message = "utmTerm must be letters, digits, dot, underscore or hyphen")
    @Size(max = 64, message = "utmTerm too long")
    private String utmTerm;

    @Pattern(regexp = "[A-Za-z0-9._-]*", message = "utmContent must be letters, digits, dot, underscore or hyphen")
    @Size(max = 64, message = "utmContent too long")
    private String utmContent;

    /** D-C1-8: unknown props land here; the service 400s utm* and click-attribution
     * ids captured this way, everything else stays silently ignored. */
    @JsonIgnore
    private final Map<String, Object> unknown = new LinkedHashMap<>();

    @JsonIgnore
    public Map<String, Object> getUnknown() {
        return unknown;
    }

    @JsonAnySetter
    void captureUnknown(String name, Object value) {
        unknown.put(name, value);
    }
}

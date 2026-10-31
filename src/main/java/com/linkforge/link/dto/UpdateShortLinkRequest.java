package com.linkforge.link.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class UpdateShortLinkRequest {

    @Size(max = 2048, message = "originalUrl too long")
    private String originalUrl;

    @Size(max = 128, message = "title too long")
    private String title;

    /** Optional; must be in the future when present. Cannot be cleared. */
    private LocalDateTime expireAt;

    /** B3: set/replace the access password (4-64 chars when present). */
    @Size(max = 64, message = "accessPassword too long")
    private String accessPassword;

    /** B3: clear the access password entirely; must not be combined with accessPassword. */
    private Boolean clearAccessPassword;

    /** C1 wave B (D-C1-6): merged into the effective originalUrl at write time, null = absent. */
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

    /** C3 A/B (D-C3-6): whole-set replace semantics — a submitted array is the link's
     * complete variant collection (empty clears back to single-target), null = untouched. */
    private List<@Valid VariantSpec> variants;

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

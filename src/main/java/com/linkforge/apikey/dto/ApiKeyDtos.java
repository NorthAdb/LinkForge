package com.linkforge.apikey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

public final class ApiKeyDtos {

    private ApiKeyDtos() {
    }

    @Data
    public static class CreateRequest {
        @NotBlank
        @Size(max = 64)
        private String name;
        /** USER|TENANT; blank/null = USER, validated in the service. */
        private String scope;
        /** NULL = default quota from linkforge.api-key. */
        @Min(1)
        private Integer quotaPerMinute;
        @Min(1)
        private Integer quotaPerDay;
    }

    /** Create response: the ONLY place the plaintext key is ever returned. */
    @Data
    public static class Created {
        private Long id;
        private String name;
        private String prefix;
        private String key;
        private String scope;
        private Long createdBy;
        private Integer quotaPerMinute;
        private Integer quotaPerDay;
        private LocalDateTime createdAt;
    }

    /** List view: no plaintext, no hash, ever. */
    @Data
    public static class View {
        private Long id;
        private String name;
        private String prefix;
        private String scope;
        private Long createdBy;
        private Integer quotaPerMinute;
        private Integer quotaPerDay;
        private LocalDateTime lastUsedAt;
        private LocalDateTime revokedAt;
        private LocalDateTime createdAt;
    }
}

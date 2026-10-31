package com.linkforge.link.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** One submitted A/B variant of a create/update request (D-C3-3/4/7). */
@Data
public class VariantSpec {

    @NotBlank(message = "variant key required")
    @Pattern(regexp = "[A-Za-z0-9_-]{1,32}", message = "variant key must be 1-32 chars of letters, digits, underscore or hyphen")
    private String key;

    @NotBlank(message = "variant url required")
    @Size(max = 2048, message = "variant url too long")
    private String url;

    /** Positive relative weight; the total never has to sum to 100. */
    @NotNull(message = "variant weight required")
    @Min(value = 1, message = "variant weight must be >= 1")
    @Max(value = 10000, message = "variant weight must be <= 10000")
    private Integer weight;
}

package com.linkforge.link.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
public class ShortLinkDetail {
    private Long id;
    private String code;
    private String originalUrl;
    private String title;
    private Integer status;
    private LocalDateTime expireAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** B3: true when an access password is set; the hash itself never leaves the API. */
    private boolean passwordProtected;
    /** C3 (D-C3-13): the link's A/B variants, or null on single-target links and list rows. */
    private List<VariantView> variants;

    public record VariantView(String key, String url, Integer weight) {
    }
}

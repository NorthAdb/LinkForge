package com.linkforge.link.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

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
}

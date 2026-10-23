package com.linkforge.link.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CreateShortLinkResponse {
    private Long id;
    private String code;
    private String originalUrl;
    private String shortPath;
}

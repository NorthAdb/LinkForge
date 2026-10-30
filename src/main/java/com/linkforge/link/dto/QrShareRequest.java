package com.linkforge.link.dto;

/** C1 wave B: optional body of POST /api/links/{code}/qr-share; null ttlSeconds = config default. */
public record QrShareRequest(Integer ttlSeconds) {
}

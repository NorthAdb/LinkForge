package com.linkforge.common.util;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIps {

    private ClientIps() {
    }

    public static String resolve(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}

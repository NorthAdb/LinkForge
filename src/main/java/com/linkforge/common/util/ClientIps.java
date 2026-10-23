package com.linkforge.common.util;

import jakarta.servlet.http.HttpServletRequest;

public final class ClientIps {

    private ClientIps() {
    }

    /**
     * X-Forwarded-For is only honored behind a trusted reverse proxy (trustXff=true);
     * by default clients can spoof that header and bypass per-IP limits, so the
     * socket address wins.
     */
    public static String resolve(HttpServletRequest request, boolean trustXff) {
        if (trustXff) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                return xff.split(",")[0].trim();
            }
        }
        return request.getRemoteAddr();
    }
}

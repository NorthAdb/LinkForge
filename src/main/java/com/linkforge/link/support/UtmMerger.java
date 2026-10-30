package com.linkforge.link.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C1 wave B (D-C1-6/7): write-time UTM merge onto original_url. Pure by design —
 * the redirect path (D-C1-31) never runs this. The canonical key order IS the
 * contract the IT pins: utm_source,utm_medium,utm_campaign,utm_term,utm_content.
 */
public final class UtmMerger {

    public static final int MAX_MERGED_LENGTH = 2048;

    private static final List<String> CANONICAL_KEYS = List.of(
            "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content");

    private UtmMerger() {
    }

    /**
     * Canonical snake_case map from the camelCase DTO fields; blank is absent. The
     * DTO's @Pattern/@Size already guarantee URL-safe values, so no encoding here.
     */
    public static Map<String, String> canonical(String utmSource, String utmMedium,
                                                String utmCampaign, String utmTerm, String utmContent) {
        String[] values = {utmSource, utmMedium, utmCampaign, utmTerm, utmContent};
        Map<String, String> utm = new LinkedHashMap<>();
        for (int i = 0; i < CANONICAL_KEYS.size(); i++) {
            String value = values[i];
            if (value != null && !value.isBlank()) {
                utm.put(CANONICAL_KEYS.get(i), value);
            }
        }
        return utm;
    }

    /**
     * Appends every utm key the raw query does not already name (string-level,
     * case-sensitive, exact name — never overwrites), keeping the existing query,
     * and re-attaches the fragment after the appended query. Returns the input
     * byte-identical when nothing is appended, null when the merged URL would
     * exceed the 2048 column cap.
     */
    public static String merge(String url, Map<String, String> utm) {
        if (utm == null || utm.isEmpty()) {
            return url;
        }
        int fragmentAt = url.indexOf('#');
        String base = fragmentAt >= 0 ? url.substring(0, fragmentAt) : url;
        String fragment = fragmentAt >= 0 ? url.substring(fragmentAt) : "";
        int queryAt = base.indexOf('?');
        String query = queryAt >= 0 ? base.substring(queryAt + 1) : null;

        StringBuilder merged = new StringBuilder(base);
        boolean pendingQueryMark = queryAt < 0;
        boolean appended = false;
        for (String key : CANONICAL_KEYS) {
            String value = utm.get(key);
            if (value == null || value.isBlank() || queryHasParam(query, key)) {
                continue;
            }
            if (pendingQueryMark) {
                merged.append('?');
                pendingQueryMark = false;
            } else if (merged.charAt(merged.length() - 1) != '?') {
                merged.append('&');
            }
            merged.append(key).append('=').append(value);
            appended = true;
        }
        if (!appended) {
            return url;
        }
        String result = merged.append(fragment).toString();
        return result.length() > MAX_MERGED_LENGTH ? null : result;
    }

    /** Exact param-name match on the raw query part; no URL parsing or decoding. */
    private static boolean queryHasParam(String query, String name) {
        if (query == null || query.isEmpty()) {
            return false;
        }
        for (String piece : query.split("&")) {
            int eq = piece.indexOf('=');
            if (piece.substring(0, eq >= 0 ? eq : piece.length()).equals(name)) {
                return true;
            }
        }
        return false;
    }
}

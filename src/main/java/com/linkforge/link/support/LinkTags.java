package com.linkforge.link.support;

import com.linkforge.common.exception.BizException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Batch 3a: link tags stored as ONE normalized comma-joined string on the link
 * row (no join table at this scale). Normalization is the write-side contract:
 * trim, lowercase, dedupe (first occurrence wins), each tag
 * {@code [a-z0-9_-]{1,32}}, at most {@value #MAX_TAGS} tags. Whole-word reads
 * go through FIND_IN_SET over the stored string, so a tag can never contain a
 * comma — the pattern above already excludes it.
 */
public final class LinkTags {

    public static final int MAX_TAGS = 10;
    private static final Pattern TAG_PATTERN = Pattern.compile("[a-z0-9_-]{1,32}");

    private LinkTags() {
    }

    public static boolean isValidTag(String tag) {
        return tag != null && TAG_PATTERN.matcher(tag).matches();
    }

    /**
     * Normalizes a submitted tag list to the stored string form; null or
     * effectively-empty input stores null (untagged). Blank entries are skipped;
     * a non-blank entry that fails the pattern is a 400.
     */
    public static String normalize(List<String> tags) {
        if (tags == null) {
            return null;
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String raw : tags) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String tag = raw.trim().toLowerCase(Locale.ROOT);
            if (!isValidTag(tag)) {
                throw new BizException(400, "invalid tag: " + raw.trim());
            }
            normalized.add(tag);
        }
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.size() > MAX_TAGS) {
            throw new BizException(400, "at most " + MAX_TAGS + " tags per link");
        }
        return String.join(",", normalized);
    }

    /** Splits the stored string back to the API's array form; null/blank -> empty list. */
    public static List<String> split(String stored) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        List<String> tags = new ArrayList<>();
        for (String tag : stored.split(",")) {
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return List.copyOf(tags);
    }
}

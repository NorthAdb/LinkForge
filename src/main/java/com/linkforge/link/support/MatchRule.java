package com.linkforge.link.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.linkforge.common.exception.BizException;
import com.linkforge.link.entity.LinkVariant;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Batch 3a dynamic routing: an optional JSON match rule on a variant narrows
 * the weighted draw at redirect time. Shape (every key optional, values inside
 * one key are OR, keys combine with AND):
 * <pre>{@code
 * {"devices":["mobile","tablet"],"os":["ios","android"],"languages":["zh","en"],
 *  "timeWindows":[{"from":"09:00","to":"18:00","tz":"Asia/Shanghai","weekdays":[1,2,3,4,5]}]}
 * }</pre>
 * A null/blank rule is a WILDCARD: the variant joins every candidate set.
 * Rules are validated strictly at write time ({@link #parse}); the redirect
 * read path uses {@link #parseLenient} so a corrupt stored row degrades to a
 * wildcard instead of breaking the jump.
 *
 * <p>The device/os value domains are the outputs of
 * {@code com.linkforge.stats.support.ClickDimensions} — that class stays the
 * single source of the classification rules, this class only pins its output
 * vocabulary. Keep the two in lockstep.
 */
public final class MatchRule {

    /** Values ClickDimensions.device() can return. */
    public static final Set<String> DEVICES = Set.of("desktop", "mobile", "tablet", "bot", "unknown");
    /** Values ClickDimensions.os() can return. */
    public static final Set<String> OS_VALUES =
            Set.of("ios", "android", "windows", "macos", "linux", "other", "unknown");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT);
    private static final Set<String> KNOWN_KEYS = Set.of("devices", "os", "languages", "timeWindows");
    private static final Set<String> KNOWN_WINDOW_KEYS = Set.of("from", "to", "tz", "weekdays");

    private final Set<String> devices;
    private final Set<String> os;
    private final Set<String> languages;
    private final List<TimeWindow> timeWindows;

    private MatchRule(Set<String> devices, Set<String> os, Set<String> languages,
                      List<TimeWindow> timeWindows) {
        this.devices = devices;
        this.os = os;
        this.languages = languages;
        this.timeWindows = timeWindows;
    }

    public record TimeWindow(LocalTime from, LocalTime to, ZoneId tz, Set<DayOfWeek> weekdays) {
    }

    /**
     * Strict write-side parse: null/blank -> null (wildcard); anything malformed
     * or out-of-domain -> 400. Returns the parsed rule for immediate use.
     */
    public static MatchRule parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new BizException(400, "matchRule is not valid JSON", ex);
        }
        if (root == null || !root.isObject()) {
            throw new BizException(400, "matchRule must be a JSON object");
        }
        root.fieldNames().forEachRemaining(name -> {
            if (!KNOWN_KEYS.contains(name)) {
                throw new BizException(400, "matchRule has unknown key: " + name);
            }
        });
        return new MatchRule(
                stringSet(root.get("devices"), "devices", DEVICES),
                stringSet(root.get("os"), "os", OS_VALUES),
                languageSet(root.get("languages")),
                windowList(root.get("timeWindows")));
    }

    /** Write-side validation that stores the submitted (trimmed) JSON verbatim. */
    public static String validate(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        parse(json);
        return json.trim();
    }

    /** Read-side parse: any failure degrades the rule to a wildcard (null). */
    public static MatchRule parseLenient(String json) {
        try {
            return parse(json);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Set<String> stringSet(JsonNode node, String key, Set<String> domain) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new BizException(400, "matchRule." + key + " must be an array");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new BizException(400, "matchRule." + key + " entries must be strings");
            }
            String value = item.asText().trim().toLowerCase(Locale.ROOT);
            if (!domain.contains(value)) {
                throw new BizException(400, "matchRule." + key + " has unknown value: " + item.asText());
            }
            values.add(value);
        }
        // An empty array is treated as an absent dimension, not "matches nothing".
        return values.isEmpty() ? null : Set.copyOf(values);
    }

    private static Set<String> languageSet(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new BizException(400, "matchRule.languages must be an array");
        }
        Set<String> values = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isTextual()) {
                throw new BizException(400, "matchRule.languages entries must be strings");
            }
            String primary = primaryTag(item.asText());
            if (primary == null || !primary.matches("[a-z]{2,8}")) {
                throw new BizException(400, "matchRule.languages has invalid language: " + item.asText());
            }
            values.add(primary);
        }
        return values.isEmpty() ? null : Set.copyOf(values);
    }

    private static List<TimeWindow> windowList(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new BizException(400, "matchRule.timeWindows must be an array");
        }
        List<TimeWindow> windows = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isObject()) {
                throw new BizException(400, "matchRule.timeWindows entries must be objects");
            }
            item.fieldNames().forEachRemaining(name -> {
                if (!KNOWN_WINDOW_KEYS.contains(name)) {
                    throw new BizException(400, "matchRule.timeWindows has unknown key: " + name);
                }
            });
            LocalTime from = parseTime(item.get("from"), "from");
            LocalTime to = parseTime(item.get("to"), "to");
            if (from.equals(to)) {
                throw new BizException(400, "matchRule.timeWindows from and to must differ");
            }
            JsonNode tzNode = item.get("tz");
            if (tzNode == null || !tzNode.isTextual() || tzNode.asText().isBlank()) {
                throw new BizException(400, "matchRule.timeWindows.tz is required");
            }
            ZoneId tz;
            try {
                tz = ZoneId.of(tzNode.asText().trim());
            } catch (Exception ex) {
                throw new BizException(400, "matchRule.timeWindows.tz is not a valid IANA zone: "
                        + tzNode.asText(), ex);
            }
            windows.add(new TimeWindow(from, to, tz, weekdaySet(item.get("weekdays"))));
        }
        return windows.isEmpty() ? null : List.copyOf(windows);
    }

    private static LocalTime parseTime(JsonNode node, String key) {
        if (node == null || !node.isTextual()) {
            throw new BizException(400, "matchRule.timeWindows." + key + " is required (HH:mm)");
        }
        try {
            return LocalTime.parse(node.asText().trim(), TIME_FORMAT);
        } catch (DateTimeParseException ex) {
            throw new BizException(400, "matchRule.timeWindows." + key + " must be HH:mm: "
                    + node.asText(), ex);
        }
    }

    private static Set<DayOfWeek> weekdaySet(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray()) {
            throw new BizException(400, "matchRule.timeWindows.weekdays must be an array");
        }
        Set<DayOfWeek> days = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (!item.isIntegralNumber()) {
                throw new BizException(400, "matchRule.timeWindows.weekdays entries must be integers 1-7");
            }
            int value = item.asInt();
            if (value < 1 || value > 7) {
                throw new BizException(400, "matchRule.timeWindows.weekdays entries must be 1-7");
            }
            days.add(DayOfWeek.of(value));
        }
        return days.isEmpty() ? null : Set.copyOf(days);
    }

    /**
     * The primary language subtag of the first Accept-Language range:
     * "zh-CN,zh;q=0.9" -> "zh". Null when the header carries no usable tag.
     */
    public static String primaryTag(String acceptLanguage) {
        if (acceptLanguage == null) {
            return null;
        }
        String first = acceptLanguage.split(",", 2)[0].trim();
        int quality = first.indexOf(';');
        if (quality >= 0) {
            first = first.substring(0, quality).trim();
        }
        if (first.isEmpty() || "*".equals(first)) {
            return null;
        }
        String primary = first.split("[-_]", 2)[0].toLowerCase(Locale.ROOT);
        return primary.isEmpty() ? null : primary;
    }

    /** AND across the dimensions the rule pins; the time-window list is an OR. */
    public boolean matches(String device, String os, String language, Instant now) {
        if (devices != null && (device == null || !devices.contains(device))) {
            return false;
        }
        if (this.os != null && (os == null || !this.os.contains(os))) {
            return false;
        }
        if (languages != null && (language == null || !languages.contains(language))) {
            return false;
        }
        if (timeWindows != null) {
            for (TimeWindow window : timeWindows) {
                if (windowMatches(window, now)) {
                    return true;
                }
            }
            return false;
        }
        return true;
    }

    private static boolean windowMatches(TimeWindow window, Instant now) {
        ZonedDateTime local = now.atZone(window.tz());
        // The weekday check uses the CURRENT local day of `now` — it is NOT
        // the day the window opened on. For an overnight window (from > to),
        // the post-midnight tail therefore matches only when the tail's own
        // weekday is also listed: e.g. a 23:00-02:00 window with
        // weekdays=[MONDAY] matches Monday 23:00-24:00 but NOT Tuesday
        // 00:00-02:00 (that tail instant is a Tuesday). Known simplification:
        // to cover an overnight tail, list both the opening and the tail
        // weekday. (An earlier version of this comment claimed start-day
        // semantics; the code has always checked the current day — audit S4.)
        if (window.weekdays() != null && !window.weekdays().contains(local.getDayOfWeek())) {
            return false;
        }
        LocalTime time = local.toLocalTime();
        if (window.from().isBefore(window.to())) {
            return !time.isBefore(window.from()) && time.isBefore(window.to());
        }
        // Overnight window (from > to): [from, 24:00) ∪ [00:00, to).
        return !time.isBefore(window.from()) || time.isBefore(window.to());
    }

    /**
     * Narrows a link's variants to the ones whose rule matches this request
     * (wildcards always qualify). When every variant carries a rule and none
     * matches, the FULL set is returned — a rule narrows the draw, it never
     * leaves the link without a target.
     */
    public static List<LinkVariant> filterCandidates(List<LinkVariant> variants, String device,
                                                     String os, String language, Instant now) {
        List<LinkVariant> candidates = new ArrayList<>(variants.size());
        for (LinkVariant variant : variants) {
            MatchRule rule = parseLenient(variant.getMatchRule());
            if (rule == null || rule.matches(device, os, language, now)) {
                candidates.add(variant);
            }
        }
        return candidates.isEmpty() ? variants : candidates;
    }
}

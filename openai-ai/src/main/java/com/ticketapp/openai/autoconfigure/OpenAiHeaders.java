package com.ticketapp.openai.autoconfigure;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Parses the {@code OPENAI_HEADERS} env var / yml property into a
 * map of HTTP headers.
 *
 * <p>Format: comma-separated {@code key=value} pairs, e.g.
 * {@code X-OpenCode-Session=ses-abc123,X-Org-Id=org-1}. Empty or
 * null input yields an empty map. Trimming is applied per entry;
 * empty entries are dropped silently.
 *
 * <p>Why a hand-rolled parser instead of {@code @ConfigurationProperties}
 * binding to {@code Map<String,String>}? Spring's binder tries to
 * convert the raw YAML/env value to the declared type
 * <em>before</em> any default handling kicks in. The empty
 * placeholder resolves to {@code ""}, which has no
 * {@code String→Map} converter — the application fails to start
 * with
 * {@code ConverterNotFoundException: No converter found capable of
 * converting from type [java.lang.String] to type
 * [java.util.Map<java.lang.String, java.lang.String>]}. Keeping
 * the property a {@code String} and parsing here keeps the wiring
 * simple and the failure mode explicit.
 *
 * <p>Header values are <strong>never logged</strong> here — a
 * header can be a session id, a tenant token, or a vendor routing
 * key. The autoconfig logs only the names (key set).
 */
public final class OpenAiHeaders {

    private OpenAiHeaders() {
    }

    /**
     * Parse the raw {@code OPENAI_HEADERS} value into an immutable
     * map. Returns an empty map when the input is null/empty or
     * contains only blank entries.
     *
     * @throws IllegalArgumentException on an entry without {@code =}
     *         or with an empty key — fails fast at boot instead of
     *         silently dropping the malformed pair.
     */
    public static Map<String, String> parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            String name = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
            if (name.isEmpty()) {
                // Distinguishes "no `=` at all" from "empty key before `=`":
                // both indicate operator-side typos but the second form is
                // common when someone writes `=value` by reflex and the
                // error message should name the actual mistake.
                if (eq < 0) {
                    throw new IllegalArgumentException(
                            "OPENAI_HEADERS entry is malformed (expected `key=value`): "
                                    + entry + " in " + raw.trim());
                }
                throw new IllegalArgumentException(
                        "OPENAI_HEADERS entry has an empty key: " + entry);
            }
            if (eq < 0) {
                throw new IllegalArgumentException(
                        "OPENAI_HEADERS entry is malformed (expected `key=value`): "
                                + entry + " in " + raw.trim());
            }
            out.put(name, trimmed.substring(eq + 1).trim());
        }
        // UnmodifiableMap wraps in place — preserves LinkedHashMap
        // iteration order, which Map.copyOf explicitly does not
        // ("iteration order is unspecified and is subject to change").
        // We want stable order so the boot log line and the SDK's
        // header insertion are deterministic.
        return Collections.unmodifiableMap(out);
    }
}
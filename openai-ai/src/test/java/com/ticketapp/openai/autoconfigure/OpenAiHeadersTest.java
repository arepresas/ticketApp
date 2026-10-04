package com.ticketapp.openai.autoconfigure;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link OpenAiHeaders#parse(String)}.
 *
 * <p>The parser is the contract between the {@code OPENAI_HEADERS}
 * env var and the SDK's {@code putHeader} builder calls. Every
 * failure mode here must surface a clear message at boot — a
 * silently-dropped malformed header would leave an operator
 * staring at a 400 from the gateway with no clue why.
 */
class OpenAiHeadersTest {

    @Test
    void nullAndBlankInputsYieldEmptyMap() {
        assertThat(OpenAiHeaders.parse(null)).isEmpty();
        assertThat(OpenAiHeaders.parse("")).isEmpty();
        assertThat(OpenAiHeaders.parse("   ")).isEmpty();
        assertThat(OpenAiHeaders.parse(",,, ,")).isEmpty();
    }

    @Test
    void parsesSingleHeader() {
        assertThat(OpenAiHeaders.parse("X-OpenCode-Session=ses-abc123"))
                .containsExactly(Map.entry("X-OpenCode-Session", "ses-abc123"));
    }

    @Test
    void parsesMultipleHeadersInOrder() {
        // LinkedHashMap preserves insertion order so the log line
        // listing header names is deterministic.
        Map<String, String> parsed = OpenAiHeaders.parse(
                "X-OpenCode-Session=ses-abc123,X-Org-Id=org-1");

        assertThat(parsed)
                .containsExactly(
                        Map.entry("X-OpenCode-Session", "ses-abc123"),
                        Map.entry("X-Org-Id", "org-1"));
    }

    @Test
    void trimsWhitespaceAroundKeysAndValues() {
        Map<String, String> parsed = OpenAiHeaders.parse(
                "  X-Header = value-with-spaces ,  X-Other=other  ");

        assertThat(parsed)
                .containsExactly(
                        Map.entry("X-Header", "value-with-spaces"),
                        Map.entry("X-Other", "other"));
    }

    @Test
    void allowsEmptyValue() {
        // Some headers carry an empty value on purpose (e.g. Content-Length
        // defaults from a gateway). Treat the absence of `=` as the
        // malformed case, but `name=` is a valid empty value.
        assertThat(OpenAiHeaders.parse("X-Empty=")).containsExactly(
                Map.entry("X-Empty", ""));
    }

    @Test
    void rejectsEntryWithoutEquals() {
        assertThatThrownBy(() -> OpenAiHeaders.parse("X-JustAKey"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_HEADERS")
                .hasMessageContaining("X-JustAKey");
    }

    @Test
    void rejectsEmptyKey() {
        assertThatThrownBy(() -> OpenAiHeaders.parse("=value-only"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty key");
    }

    @Test
    void rejectsEntryThatIsOnlyEquals() {
        assertThatThrownBy(() -> OpenAiHeaders.parse("=,"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsWhitespaceOnlyKey() {
        // "   =value" should fail because the trimmed key is empty.
        assertThatThrownBy(() -> OpenAiHeaders.parse("   =value"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty key");
    }

    @Test
    void valuesContainingCommasAreNotSupported() {
        // Honest documentation of the format constraint. Splitting on `,`
        // means a value containing a literal comma is unreachable through
        // this parser — operators who need it must add a real Map binding.
        // A header like `X-Trace=a,b,c` will parse as X-Trace=a plus a
        // garbage entry `b=c`. Verified here so the limitation is explicit.
        assertThatThrownBy(() -> OpenAiHeaders.parse("X-Trace=a,b"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("X-Trace=a")
                .hasMessageContaining("b");
    }
}
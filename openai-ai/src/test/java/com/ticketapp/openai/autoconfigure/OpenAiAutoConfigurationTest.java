package com.ticketapp.openai.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link OpenAiAutoConfiguration}.
 *
 * <p>The autoconfig wires {@link com.openai.client.OpenAIClient} from
 * {@link OpenAiProperties}. Tests instantiate the class directly
 * (no Spring context needed) and verify the bean factory produces a
 * usable client. The placeholder / blank-key guard lives here so a
 * missing credential fails fast at boot instead of 401-ing every tick.
 */
class OpenAiAutoConfigurationTest {

    private static final OpenAiProperties PROPS = new OpenAiProperties(
            "https://api.openai.com/v1", "test-api-key", "gpt-4o-mini", 30_000L, 0.0, 16384,
            "", false);

    @Test
    void openAIClientReturnsNonNullClient() {
        OpenAiAutoConfiguration config = new OpenAiAutoConfiguration();

        var client = config.openAIClient(PROPS);

        assertThat(client).isNotNull();
    }

    @Test
    void openAIClientIsRepeatableForSameProperties() {
        // The factory has no per-call state — calling it twice with
        // the same properties yields two independent clients. Cheap
        // check that the autoconfig is safe to invoke multiple times
        // (e.g. when a Spring context is refreshed in tests).
        OpenAiAutoConfiguration config = new OpenAiAutoConfiguration();

        var first = config.openAIClient(PROPS);
        var second = config.openAIClient(PROPS);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void openAIClientRejectsPlaceholderKey() {
        OpenAiAutoConfiguration config = new OpenAiAutoConfiguration();
        var props = new OpenAiProperties(
                "https://api.openai.com/v1", "dev-placeholder", "gpt-4o-mini", 30_000L, 0.0, 16384,
                "", false);

        assertThatThrownBy(() -> config.openAIClient(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void openAIClientRejectsBlankKey() {
        OpenAiAutoConfiguration config = new OpenAiAutoConfiguration();
        var props = new OpenAiProperties(
                "https://api.openai.com/v1", "  ", "gpt-4o-mini", 30_000L, 0.0, 16384,
                "", false);

        assertThatThrownBy(() -> config.openAIClient(props))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void openAIClientAcceptsCustomHeaders() {
        // Smoke check that the headers path does not throw and yields a
        // usable client. A wire-level test that the header actually
        // reaches the wire lives in the IT module with a MockWebServer
        // — here we only assert the factory accepts the configuration.
        OpenAiAutoConfiguration config = new OpenAiAutoConfiguration();
        var props = new OpenAiProperties(
                "https://api.openai.com/v1", "test-api-key", "gpt-4o-mini", 30_000L, 0.0, 16384,
                "X-OpenCode-Session=ses-1234,X-Org-Id=org-1", true);

        var client = config.openAIClient(props);

        assertThat(client).isNotNull();
    }
}

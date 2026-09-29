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
            "https://api.openai.com/v1", "test-api-key", "gpt-4o-mini", 30_000L, 0.0, 16384);

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
                "https://api.openai.com/v1", "dev-placeholder", "gpt-4o-mini", 30_000L, 0.0, 16384);

        assertThatThrownBy(() -> config.openAIClient(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }

    @Test
    void openAIClientRejectsBlankKey() {
        OpenAiAutoConfiguration config = new OpenAiAutoConfiguration();
        var props = new OpenAiProperties(
                "https://api.openai.com/v1", "  ", "gpt-4o-mini", 30_000L, 0.0, 16384);

        assertThatThrownBy(() -> config.openAIClient(props))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

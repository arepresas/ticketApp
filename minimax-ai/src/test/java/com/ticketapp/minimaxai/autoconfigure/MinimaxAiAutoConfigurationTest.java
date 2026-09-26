package com.ticketapp.minimaxai.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link MinimaxAiAutoConfiguration}.
 *
 * <p>The autoconfig wires {@link com.openai.client.OpenAIClient} from
 * {@link MinimaxAiProperties}. Tests instantiate the class directly
 * (no Spring context needed) and verify the bean factory produces a
 * usable client. The placeholder / blank-key guard lives here so a
 * missing credential fails fast at boot instead of 401-ing every tick.
 */
class MinimaxAiAutoConfigurationTest {

    private static final MinimaxAiProperties PROPS = new MinimaxAiProperties(
            "https://api.minimax.io/v1", "test-api-key", "MiniMax-M3", 30_000L, 0.0, 16384);

    @Test
    void openAIClientReturnsNonNullClient() {
        MinimaxAiAutoConfiguration config = new MinimaxAiAutoConfiguration();

        var client = config.openAIClient(PROPS);

        assertThat(client).isNotNull();
    }

    @Test
    void openAIClientIsRepeatableForSameProperties() {
        // The factory has no per-call state — calling it twice with
        // the same properties yields two independent clients. Cheap
        // check that the autoconfig is safe to invoke multiple times
        // (e.g. when a Spring context is refreshed in tests).
        MinimaxAiAutoConfiguration config = new MinimaxAiAutoConfiguration();

        var first = config.openAIClient(PROPS);
        var second = config.openAIClient(PROPS);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first).isNotSameAs(second);
    }

    @Test
    void openAIClientRejectsPlaceholderKey() {
        MinimaxAiAutoConfiguration config = new MinimaxAiAutoConfiguration();
        var props = new MinimaxAiProperties(
                "https://api.minimax.io/v1", "dev-placeholder", "MiniMax-M3", 30_000L, 0.0, 16384);

        assertThatThrownBy(() -> config.openAIClient(props))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MINIMAX_API_KEY");
    }

    @Test
    void openAIClientRejectsBlankKey() {
        MinimaxAiAutoConfiguration config = new MinimaxAiAutoConfiguration();
        var props = new MinimaxAiProperties(
                "https://api.minimax.io/v1", "  ", "MiniMax-M3", 30_000L, 0.0, 16384);

        assertThatThrownBy(() -> config.openAIClient(props))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

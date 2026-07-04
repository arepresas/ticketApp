package com.ticketapp.minimaxai.autoconfigure;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MinimaxAiAutoConfiguration}.
 *
 * <p>The autoconfig wires {@link com.openai.client.OpenAIClient} from
 * {@link MinimaxAiProperties}. Tests instantiate the class directly
 * (no Spring context needed) and verify the bean factory produces a
 * usable client. The placeholder / blank-key guards live on
 * {@link com.ticketapp.minimaxai.MiniMaxApiClient#create} (covered
 * by {@code MiniMaxApiClientTest}); the autoconfig delegates to that
 * factory via Spring DI.
 */
class MinimaxAiAutoConfigurationTest {

    private static final MinimaxAiProperties PROPS = new MinimaxAiProperties(
            "https://api.minimax.io/v1", "test-api-key", "MiniMax-M3", 30_000L);

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
}
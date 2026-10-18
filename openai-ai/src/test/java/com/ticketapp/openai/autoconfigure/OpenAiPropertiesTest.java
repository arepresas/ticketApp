package com.ticketapp.openai.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenAiProperties}. Focus is the reasoning-effort
 * contract added after the 2026-10-18 incident: hybrid-reasoning
 * providers bill reasoning inside {@code max_completion_tokens}, so the
 * effort has to be pinned to something the operator actually chose —
 * and the one value the provider rejects has to fail at boot rather
 * than on the first ticket of the morning.
 */
class OpenAiPropertiesTest {

    @Test
    void normalisesReasoningEffortToLowerCase() {
        assertThat(propsWithEffort("LOW").reasoningEffort()).isEqualTo("low");
        assertThat(propsWithEffort("  Medium  ").reasoningEffort()).isEqualTo("medium");
    }

    @Test
    void acceptsEverySdkVocabularyExceptNone() {
        for (String effort : new String[] {"minimal", "low", "medium", "high", "xhigh", "max"}) {
            assertThat(propsWithEffort(effort).reasoningEffort()).isEqualTo(effort);
        }
    }

    @Test
    void rejectsNoneBecauseTheProviderAnswersFourHundred() {
        assertThatThrownBy(() -> propsWithEffort("none"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reasoning-effort=none is not accepted")
                .hasMessageContaining("2013");
    }

    @Test
    void rejectsBlankEffortWithTheOperatorVariableNamed() {
        assertThatThrownBy(() -> propsWithEffort("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("OPENAI_REASONING_EFFORT");
    }

    @Test
    void rejectsUnknownEffortAndListsTheValidOnes() {
        assertThatThrownBy(() -> propsWithEffort("turbo"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("turbo")
                .hasMessageContaining("low");
    }

    @Test
    void keepsTheOcrBudgetSeparateFromTheExtractionBudget() {
        OpenAiProperties props = propsWithEffort("low");

        assertThat(props.ocrMaxCompletionTokens()).isGreaterThan(props.maxCompletionTokens());
    }

    private static OpenAiProperties propsWithEffort(String reasoningEffort) {
        return new OpenAiProperties(
                "https://api.openai.com/v1",
                "test-api-key",
                "gpt-4o-mini",
                30_000L,
                0.0,
                16_384, // extraction budget
                65_536, // OCR budget
                reasoningEffort,
                "",
                false);
    }
}

package com.ticketapp.openai.autoconfigure;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Provider-specific configuration for the provider implementation
 * (ADR 0007). Bound from {@code application.yml} under
 * {@code ticketapp.ai.openai.*}.
 *
 * <p>Why a separate properties class from the BFF's
 * {@code AiProperties}? The BFF owns provider-agnostic knobs
 * (kill switch, cron, batch size); this class owns
 * provider-specific knobs (base URL, key, model id, timeout,
 * sampling temperature, token budget). The
 * split keeps the BFF free of provider imports while still letting
 * an operator tune the active provider without code changes.
 *
 * <p>Validation runs at binding time. {@code application.yml} bakes
 * no defaults in (operator-layer contract — values come from env,
 * {@code .env}, or active-profile YAML), so a missing
 * {@code OPENAI_API_KEY} or {@code OPENAI_MODEL} fails the
 * autoconfiguration at boot rather than silently producing a
 * 401-every-tick loop.
 */
@ConfigurationProperties(prefix = "ticketapp.ai.openai")
@Validated
public record OpenAiProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotBlank String model,
        @Positive long timeoutMs,
        @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
        @Positive int maxCompletionTokens
) { }

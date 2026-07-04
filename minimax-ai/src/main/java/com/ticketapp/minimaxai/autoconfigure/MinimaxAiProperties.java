package com.ticketapp.minimaxai.autoconfigure;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Provider-specific configuration for the MiniMax implementation
 * (ADR 0007). Bound from {@code application.yml} under
 * {@code ticketapp.ai.minimax.*}.
 *
 * <p>Why a separate properties class from the BFF's
 * {@code AiProperties}? The BFF owns provider-agnostic knobs
 * (kill switch, cron, batch size); this class owns
 * provider-specific knobs (base URL, key, model id, timeout). The
 * split keeps the BFF free of provider imports while still letting
 * an operator tune the active provider without code changes.
 *
 * <p>Validation runs at binding time. {@code application.yml} bakes
 * no defaults in (operator-layer contract — values come from env,
 * {@code .env}, or active-profile YAML), so a missing
 * {@code MINIMAX_API_KEY} or {@code MINIMAX_MODEL} fails the
 * autoconfiguration at boot rather than silently producing a
 * 401-every-tick loop.
 */
@ConfigurationProperties(prefix = "ticketapp.ai.minimax")
@Validated
public record MinimaxAiProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotBlank String model,
        @Positive long timeoutMs
) { }
package com.ticketapp.bff.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Provider-agnostic configuration for the AI extraction pipeline
 * (ADR 0006 + ADR 0007).
 *
 * <p>Bound from {@code application.yml} under {@code ticketapp.ai.*}.
 * Carries only the knobs that the orchestrator (BFF) cares about
 * — kill switch, cron, batch size, retry budget. Provider-specific
 * knobs (model id, base URL, API key, timeout) live with the
 * provider module under {@code ticketapp.ai.{provider}.*} — for
 * MiniMax today, {@code ticketapp.ai.minimax.*} in
 * {@link com.ticketapp.minimaxai.autoconfigure.MinimaxAiProperties}.
 *
 * <p>No defaults are baked into YAML — the operator layer (env
 * vars, {@code .env}, application profiles) is the single source
 * of truth. But the shape IS validated at binding time so a typo
 * (empty cron, batch size 0) fails the boot with a clear message
 * instead of a cryptic scheduler failure at 3am.
 */
@ConfigurationProperties(prefix = "ticketapp.ai")
@Validated
public record AiProperties(
        boolean enabled,
        @NotBlank String cron,
        @Positive int batchSize,
        @PositiveOrZero int retryAttempts
) { }

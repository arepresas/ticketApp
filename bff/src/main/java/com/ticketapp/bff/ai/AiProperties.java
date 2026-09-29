package com.ticketapp.bff.ai;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Objects;

/**
 * Provider-agnostic configuration for the AI extraction pipeline
 * (ADR 0006 + ADR 0007).
 *
 * <p>Bound from {@code application.yml} under {@code ticketapp.ai.*}.
 * Carries only the knobs that the orchestrator (BFF) cares about
 * — kill switch, cron, batch size, retry budget. Provider-specific
 * knobs (model id, base URL, API key, timeout) live with the
 * provider module under {@code ticketapp.ai.{provider}.*} — for
 * today, {@code ticketapp.ai.openai.*} in
 * {@link com.ticketapp.openai.autoconfigure.OpenAiProperties}.
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
        @PositiveOrZero int retryAttempts,
        /**
         * How long a ticket may sit in {@code IN_ANALYSIS} before the
         * scheduler assumes the worker holding it died and re-queues
         * it. Defaults to 10 minutes, which is far above the provider
         * call timeout (30 s in prod) so a slow-but-alive worker is
         * never robbed of its claim.
         */
        @DefaultValue("10m") Duration staleAnalysisTimeout
) {
    public AiProperties {
        Objects.requireNonNull(staleAnalysisTimeout, "staleAnalysisTimeout is required");
        // @Scheduled consumes the raw property, so nothing else would
        // ever read this field — and a malformed cron would otherwise
        // blow up the scheduler at init with an opaque message.
        // Parse it here so the failure is a boot failure with a
        // readable cause.
        try {
            org.springframework.scheduling.support.CronExpression.parse(cron);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "ticketapp.ai.cron is not a valid cron expression: " + cron, e);
        }
    }
}

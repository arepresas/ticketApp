package com.ticketapp.bff.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

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
 * <p>By design this record carries <b>no validation annotations</b>:
 * the BFF refuses to bake defaults into its YAML so the operator
 * layer (env vars, {@code .env}, application profiles) is the
 * single source of truth. Validation lives in the provider modules
 * where it can target concrete constraints (key shape, model id,
 * etc.). A misconfiguration here produces a runtime failure —
 * either at the first scheduler tick (caught + reverted per
 * ADR 0006 D4) or at the first downstream call — rather than a
 * boot-time validator.
 */
@ConfigurationProperties(prefix = "ticketapp.ai")
public record AiProperties(
        boolean enabled,
        String cron,
        int batchSize,
        int retryAttempts
) { }
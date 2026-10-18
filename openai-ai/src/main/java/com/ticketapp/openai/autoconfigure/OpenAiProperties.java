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
        @Positive int maxCompletionTokens,
        /**
         * Completion budget for the OCR call
         * ({@link com.ticketapp.openai.OpenAiApiClient#transcribeImage}).
         *
         * <p><b>Why a second budget instead of reusing
         * {@link #maxCompletionTokens}.</b> Hybrid-reasoning models
         * (MiniMax-M3.1-Flash-Preview, o-series, …) bill
         * {@code reasoning_content} <em>inside</em>
         * {@code max_completion_tokens} — the provider's own docs say
         * a value that is too small "yields {@code finish_reason:
         * "length"} with an empty {@code content}". OCR is the call
         * that loses that race: a 1 MB receipt photo with no
         * {@code response_format} and no schema to anchor the reply
         * is exactly the shape that makes the model deliberate, and
         * the prompt-level "don't think" instructions cannot suppress
         * a built-in reasoning channel. Structured extraction, which
         * pins {@code response_format: json_object}, has stayed
         * well inside the smaller budget — 2026-10-18 incident: OCR
         * burned 16383 of 16384 tokens on reasoning and returned
         * {@code content: null}, while the extraction of the very
         * same ticket succeeded at 649 tokens.
         *
         * <p>Default 65536. The provider recommends 131072 for this
         * model family and accepts up to 524288; 65536 leaves room
         * for a long reasoning pass plus a 30-line transcription
         * without tripling the worst-case bill of a runaway.
         */
        @Positive int ocrMaxCompletionTokens,
        /**
         * Reasoning effort sent as {@code reasoning_effort} on every
         * provider request. Bound from
         * {@code ticketapp.ai.openai.reasoning-effort} via
         * {@code OPENAI_REASONING_EFFORT}.
         *
         * <p><b>Why the operator has to set this.</b> Providers
         * default hybrid-reasoning models to their <em>highest</em>
         * effort (MiniMax: {@code max}), and the more effort the
         * model spends, the more of {@code max_completion_tokens} it
         * burns before producing the answer that actually reaches
         * {@code content}. {@code low} is the sweet spot for this
         * workload: transcribing a receipt and emitting a fixed JSON
         * schema are perception-and-formatting tasks, not
         * multi-step-planning tasks, and the measured cost is a
         * fraction of the default with no loss of fidelity.
         *
         * <p>{@code none} is rejected at boot — MiniMax answers
         * HTTP 400 (err 2013) because M3.1 requires adaptive
         * thinking, and OpenAI's {@code none} means something else
         * entirely. Everything else in the SDK's vocabulary
         * ({@code minimal}, {@code low}, {@code medium},
         * {@code high}, {@code xhigh}, {@code max}) is accepted so a
         * future provider swap does not need a code change.
         */
        @NotBlank String reasoningEffort,
        /**
         * Comma-separated {@code key=value} list of extra HTTP
         * headers attached to every provider request. Operator-layer
         * escape hatch for gateways that require a session token or
         * routing header (e.g. opencode-go's {@code X-OpenCode-Session})
         * without baking the contract into the provider code. Bound
         * from {@code ticketapp.ai.openai.custom-headers} via
         * {@code OPENAI_HEADERS}. Parsed in {@link OpenAiHeaders};
         * values are <strong>never logged</strong> — a header can be
         * a session id, a tenant token, or a vendor routing key.
         *
         * <p>Why a {@code String} and not {@code Map<String,String>}?
         * Spring's binder tries to convert the raw YAML/env value to
         * the declared type before any default handling: an empty
         * placeholder resolves to {@code ""}, which has no
         * {@code String→Map} converter, and the whole context fails
         * to start. Keeping it a {@code String} sidesteps the issue
         * and gives us an explicit, validated parser we control.
         */
        String customHeaders,
        /**
         * Opt-in SDK HTTP debug logging. The OpenAI Java SDK ships its
         * own {@code logLevel(LogLevel.DEBUG)} toggle that emits request
         * and response lines through SLF4J; it is <strong>off</strong>
         * by default and toggling {@code logging.level.com.openai=DEBUG}
         * alone does not turn it on (the SDK gates logging behind its
         * own level, not the SLF4J level). Setting this to {@code true}
         * activates it so the operator can see the wire shape — useful
         * for diagnosing gateway rejections with empty bodies. Bound
         * from {@code ticketapp.ai.openai.debug-http} via
         * {@code OPENAI_DEBUG_HTTP}. Default {@code false} because the
         * SDK's debug output includes header lines and is otherwise
         * chatty.
         */
        boolean debugHttp
) {
    /**
     * The reasoning-effort values the OpenAI SDK's
     * {@code ReasoningEffort} enum can serialise, minus {@code none}.
     */
    private static final java.util.Set<String> SUPPORTED_REASONING_EFFORTS =
            java.util.Set.of("minimal", "low", "medium", "high", "xhigh", "max");

    public OpenAiProperties {
        if (customHeaders == null) {
            customHeaders = "";
        }
        reasoningEffort = normaliseReasoningEffort(reasoningEffort);
    }

    /**
     * Lower-cases and validates the configured reasoning effort so a
     * typo or a provider-illegal {@code none} fails the boot with a
     * readable cause instead of surfacing as an opaque HTTP 400 on
     * the first ticket of the day.
     */
    private static String normaliseReasoningEffort(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "ticketapp.ai.openai.reasoning-effort is required; set "
                            + "OPENAI_REASONING_EFFORT to one of " + SUPPORTED_REASONING_EFFORTS
                            + " (low is the recommended default for receipt OCR)");
        }
        String normalised = value.trim().toLowerCase(java.util.Locale.ROOT);
        if ("none".equals(normalised)) {
            throw new IllegalArgumentException(
                    "ticketapp.ai.openai.reasoning-effort=none is not accepted: hybrid-reasoning "
                            + "providers reject it (MiniMax answers HTTP 400 err 2013 — M3.1 requires "
                            + "adaptive thinking) and OpenAI's none carries different semantics. Use 'low'.");
        }
        if (!SUPPORTED_REASONING_EFFORTS.contains(normalised)) {
            throw new IllegalArgumentException(
                    "ticketapp.ai.openai.reasoning-effort=" + value + " is not a known value; expected one of "
                            + SUPPORTED_REASONING_EFFORTS);
        }
        return normalised;
    }
}

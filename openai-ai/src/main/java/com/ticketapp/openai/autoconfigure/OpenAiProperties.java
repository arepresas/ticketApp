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
    public OpenAiProperties {
        if (customHeaders == null) {
            customHeaders = "";
        }
    }
}

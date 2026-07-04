package com.ticketapp.domain.ai;

/**
 * Provider-neutral outcome of a {@link ReceiptExtractor#extract}
 * call (ADR 0007).
 *
 * <p>Carries the parsed {@link ReceiptExtractionResult}, the raw
 * reply the provider returned, and the model identifier the
 * implementation used. The raw reply is provider-defined text —
 * what the model actually said before any parsing — and is
 * persisted alongside the structured result for audit and
 * re-parsing-on-prompt-change (ADR 0006). It may be {@code null}
 * when the implementation has no notion of a "raw reply" (a future
 * provider that returns only structured output).
 *
 * <p>The {@code model} field is the implementation's choice of
 * model for this call. The orchestrator writes it into the
 * {@code ticket_extractions.model} column for audit. Each
 * implementation reads its model name from its own
 * provider-specific configuration so the BFF stays model-agnostic.
 *
 * <p>The port deliberately does not split this into multiple
 * method calls: a single round-trip is required so the raw text
 * and the model id are unambiguously associated with the result
 * they produced.
 */
public record ReceiptExtraction(
        ReceiptExtractionResult result,
        String rawReply,
        String model
) {
    public ReceiptExtraction {
        if (result == null) throw new NullPointerException("result");
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        // rawReply may be null — see Javadoc above.
    }
}
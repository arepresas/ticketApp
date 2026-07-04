package com.ticketapp.domain.ai;

/**
 * Provider-agnostic port for receipt extraction (ADR 0007).
 *
 * <p>The orchestrator (BFF) depends only on this interface — never on
 * a provider-specific class. Implementations live in dedicated Maven
 * modules ({@code minimax-ai}, future {@code openai-ai}, etc.) and
 * are wired through Spring Boot autoconfiguration: whichever provider
 * module is on the classpath supplies the bean.
 *
 * <p>Implementations must:
 * <ul>
 *   <li>Translate their internal failures (HTTP, parse, network) into
 *       {@link ReceiptExtractionException}.</li>
 *   <li>Return a {@link ReceiptExtraction} whose
 *       {@link ReceiptExtraction#result()} passes the constructor
 *       validation. A result that fails domain validation (e.g. blank
 *       merchant) is an implementation bug and should surface as an
 *       exception, not as a malformed record.</li>
 *   <li>Populate {@link ReceiptExtraction#rawReply()} with whatever
 *       raw text the provider returned, when applicable. The value is
 *       persisted to {@code ticket_extractions.raw_response_text} for
 *       audit; passing {@code null} is allowed when the provider has
 *       no notion of a raw reply.</li>
 *   <li>Not retain the request bytes beyond the call. Privacy: the
 *       orchestrator persists the {@code rawReply} only; the original
 *       bytes must not be cached in the implementation.</li>
 * </ul>
 */
public interface ReceiptExtractor {

    /**
     * Run extraction on the given request and return both the parsed
     * result and the raw reply. Throws {@link ReceiptExtractionException}
     * when the provider cannot complete the call for any reason that
     * is not already a {@code ReceiptExtractionResult} validation
     * failure (which would be a bug, not a runtime condition).
     *
     * @throws ReceiptExtractionException on any provider failure
     */
    ReceiptExtraction extract(ReceiptExtractionRequest request)
            throws ReceiptExtractionException;
}
package com.ticketapp.domain.ai;

import com.ticketapp.domain.exceptions.TicketAppException;

/**
 * Provider-neutral exception thrown by a
 * {@link DocumentTextExtractor} when the OCR step cannot complete.
 *
 * <p>Unchecked: the upload flow treats OCR as best-effort and must
 * not declare it at every layer. Mirrors
 * {@link ReceiptExtractionException} so the orchestrator (BFF) can
 * use the same try/catch shape around both ports. The constructor
 * with {@code statusCode == 0} covers failures that did not involve
 * an HTTP response (connection refused, parse error, provider
 * refusal, PDFBox I/O error, rasterize failure, etc.).
 *
 * <p>Carrying the upstream HTTP status where it exists lets the
 * dashboard distinguish "provider is down" (5xx, retriable) from
 * "provider rejected our request" (4xx, usually a config problem)
 * without reaching into a provider-specific exception class.
 *
 * <p>No Lombok by project rule: the domain module depends only on
 * {@code java.*}.
 */
public class DocumentTextExtractionException extends TicketAppException {

    /** Stable code for logs and API responses. Named ERROR_CODE rather than
     *  CODE so it cannot be confused with the inherited {@code code()} accessor. */
    public static final String ERROR_CODE = "DOCUMENT_TEXT_EXTRACTION_FAILED";

    private final int statusCode;

    public DocumentTextExtractionException(int statusCode, String message) {
        super(ERROR_CODE, message);
        this.statusCode = statusCode;
    }

    public DocumentTextExtractionException(int statusCode, String message, Throwable cause) {
        super(ERROR_CODE, message, cause);
        this.statusCode = statusCode;
    }

    public int statusCode() {
        return statusCode;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "[statusCode=" + statusCode + ", message=" + getMessage() + "]";
    }
}

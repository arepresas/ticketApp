package com.ticketapp.domain.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link ReceiptExtractionRequest} (ADR 0007).
 *
 * <p>Covers three concerns:
 * <ul>
 *   <li>Field validation: content must be non-null and non-empty;
 *       contentType must be non-blank.</li>
 *   <li>{@link #isPdf()} mime-type detection.</li>
 *   <li>Defensive equals/hashCode/toString overrides — records
 *       auto-generate these with reference semantics for arrays,
 *       which would make two requests with the same image bytes
 *       compare as different. The override makes equality
 *       content-based (mirrors Ticket#fileData).</li>
 * </ul>
 */
class ReceiptExtractionRequestTest {

    private static final byte[] PNG = new byte[]{(byte) 0x89, 'P', 'N', 'G'};

    @Test
    void carriesContentAndContentType() {
        ReceiptExtractionRequest r = new ReceiptExtractionRequest(PNG, "image/png");

        assertEquals(PNG, r.content());
        assertEquals("image/png", r.contentType());
    }

    @Test
    void rejectsNullContent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionRequest(null, "image/png"));
    }

    @Test
    void rejectsEmptyContent() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionRequest(new byte[0], "image/png"));
    }

    @Test
    void rejectsBlankContentType() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionRequest(PNG, "  "));
    }

    @Test
    void rejectsNullContentType() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtractionRequest(PNG, null));
    }

    @Test
    void isPdfDetectsPdfMime() {
        ReceiptExtractionRequest r = new ReceiptExtractionRequest(PNG, "application/pdf");

        assertTrue(r.isPdf());
    }

    @Test
    void isPdfHandlesMimeWithParameters() {
        // Browsers sometimes send "application/pdf; charset=binary" —
        // the prefix check must tolerate that.
        ReceiptExtractionRequest r = new ReceiptExtractionRequest(
                PNG, "application/pdf; charset=binary");

        assertTrue(r.isPdf());
    }

    @Test
    void isPdfReturnsFalseForImages() {
        ReceiptExtractionRequest r = new ReceiptExtractionRequest(PNG, "image/png");

        assertFalse(r.isPdf());
    }

    @Test
    void isPdfIsCaseInsensitive() {
        // The mime may come in any case from the uploader.
        ReceiptExtractionRequest r = new ReceiptExtractionRequest(PNG, "Application/PDF");

        assertTrue(r.isPdf());
    }

    @Test
    void equalsTreatsByteArrayContentBased() {
        // The override matters: two requests with equal bytes
        // compare as equal even when constructed independently.
        ReceiptExtractionRequest a = new ReceiptExtractionRequest(PNG, "image/png");
        ReceiptExtractionRequest b = new ReceiptExtractionRequest(PNG.clone(), "image/png");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void equalsDistinguishesDifferentContent() {
        ReceiptExtractionRequest a = new ReceiptExtractionRequest(PNG, "image/png");
        ReceiptExtractionRequest b = new ReceiptExtractionRequest(
                new byte[]{1, 2, 3}, "image/png");

        assertNotEquals(a, b);
    }

    @Test
    void equalsDistinguishesDifferentContentType() {
        ReceiptExtractionRequest a = new ReceiptExtractionRequest(PNG, "image/png");
        ReceiptExtractionRequest b = new ReceiptExtractionRequest(PNG, "image/jpeg");

        assertNotEquals(a, b);
    }

    @Test
    void toStringShowsByteCountNotRawBytes() {
        // Critical: the bytes may contain a receipt image — toString
        // must not dump them into a log line.
        ReceiptExtractionRequest r = new ReceiptExtractionRequest(PNG, "image/png");

        String rendered = r.toString();
        assertTrue(rendered.contains("4 bytes"), "expected byte count: " + rendered);
        assertTrue(rendered.contains("image/png"), "expected mime: " + rendered);
        // No raw byte dump — assert no hex escape that would betray
        // the byte values (PNG header starts with 0x89 which would
        // render as a non-printable char otherwise).
        assertFalse(rendered.contains("89"), "toString must not leak raw bytes: " + rendered);
    }
}
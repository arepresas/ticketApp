package com.ticketapp.domain.ai;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Unit tests for {@link ReceiptExtraction} (ADR 0007).
 *
 * <p>The contract: a non-null structured result and a non-blank
 * model id are mandatory; rawReply may be null (some provider
 * implementations have no notion of a "raw reply").
 */
class ReceiptExtractionTest {

    private static ReceiptExtractionResult sampleResult() {
        return new ReceiptExtractionResult(
                "X", LocalDate.of(2026, Month.JANUARY, 1), null,
                List.of(), BigDecimal.ONE, "EUR");
    }

    @Test
    void carriesResultRawReplyAndModel() {
        ReceiptExtractionResult result = sampleResult();

        ReceiptExtraction e = new ReceiptExtraction(result, "raw-text", "MiniMax-M3");

        assertSame(result, e.result());
        assertEquals("raw-text", e.rawReply());
        assertEquals("MiniMax-M3", e.model());
    }

    @Test
    void rawReplyMayBeNull() {
        // Some future provider implementations return only structured
        // output and have no concept of a "raw reply". The record
        // explicitly allows null on rawReply.
        ReceiptExtraction e = new ReceiptExtraction(sampleResult(), null, "MiniMax-M3");

        assertNull(e.rawReply());
    }

    @Test
    void rejectsNullResult() {
        NullPointerException ex = assertThrows(NullPointerException.class,
                () -> new ReceiptExtraction(null, "raw", "MiniMax-M3"));
        assertEquals("result", ex.getMessage());
    }

    @Test
    void rejectsNullModel() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtraction(sampleResult(), "raw", null));
        assertEquals("model must not be blank", ex.getMessage());
    }

    @Test
    void rejectsBlankModel() {
        assertThrows(IllegalArgumentException.class,
                () -> new ReceiptExtraction(sampleResult(), "raw", "   "));
    }
}
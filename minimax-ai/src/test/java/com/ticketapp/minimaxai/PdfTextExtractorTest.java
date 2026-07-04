package com.ticketapp.minimaxai;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link PdfTextExtractor}.
 *
 * <p>The class is a thin wrapper around PDFBox 3.x's text stripper,
 * but it has two contract points worth pinning:
 * <ul>
 *   <li>Empty / null input returns an empty string (not null, not
 *       throws) — the orchestrator treats empty text as a
 *       recoverable skip signal.</li>
 *   <li>Valid PDF bytes round-trip through the stripper to yield the
 *       text the page content stream wrote.</li>
 * </ul>
 */
class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void emptyBytesReturnsEmptyString() throws IOException {
        assertThat(extractor.extract(new byte[0])).isEmpty();
    }

    @Test
    void nullBytesReturnsEmptyString() throws IOException {
        // Defensive: the orchestrator's null-check normally catches
        // this, but if a future caller skips it the extractor must
        // not throw — empty is the documented "nothing to extract"
        // signal.
        assertThat(extractor.extract(null)).isEmpty();
    }

    @Test
    void extractsTextFromSimplePdf() throws IOException {
        // Build a minimal one-page PDF in memory with known text,
        // then verify the extractor surfaces it.
        byte[] pdf = buildPdfWithText("MERCADONA");

        String extracted = extractor.extract(pdf);

        assertThat(extracted).contains("MERCADONA");
    }

    @Test
    void extractsMultilineTextFromPdf() throws IOException {
        // The PDFBox text stripper joins visual lines with newlines.
        // A two-line content stream must round-trip both lines.
        // PDFBox's WinAnsi-encoded Helvetica rejects the '\n' control
        // character in showText, so we render each line as a separate
        // showText call at descending Y offsets — same final layout,
        // no encoding error.
        byte[] pdf = buildPdfWithMultipleLines("LINE ONE", "LINE TWO");

        String extracted = extractor.extract(pdf);

        assertThat(extracted).contains("LINE ONE");
        assertThat(extracted).contains("LINE TWO");
    }

    @Test
    void closesDocumentOnSuccess() throws IOException {
        // We can't easily assert "closed" from outside, but the
        // happy path must not leak resources. Run it twice on the
        // same extractor instance and verify both succeed — a leaked
        // file handle would surface as "too many open files" only
        // under load, but a leaked document would block the second
        // extraction if the implementation accidentally held a
        // reference.
        byte[] pdf = buildPdfWithText("hello");
        extractor.extract(pdf);
        extractor.extract(pdf);
        extractor.extract(pdf);

        // If we got here without exception, the document close path
        // is exercised repeatedly. No direct assertion needed.
    }

    /** Build a one-page PDF with the given text in default Helvetica. */
    private static byte[] buildPdfWithText(String text) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }

    /** Build a one-page PDF with several lines at descending Y offsets. */
    private static byte[] buildPdfWithMultipleLines(String... lines) throws IOException {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                float y = 750;
                for (String line : lines) {
                    cs.beginText();
                    cs.newLineAtOffset(50, y);
                    cs.showText(line);
                    cs.endText();
                    y -= 20;
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return out.toByteArray();
        }
    }
}
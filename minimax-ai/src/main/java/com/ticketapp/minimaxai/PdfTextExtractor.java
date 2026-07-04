package com.ticketapp.minimaxai;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Extracts plain text from a digital-born PDF.
 *
 * <p>Used by the AI extraction pipeline (ADR 0006, D3): MiniMax's
 * chat-completions endpoint accepts images and videos but not PDFs,
 * so PDF receipts must be reduced to text before they can be sent.
 *
 * <p>PDFBox 3.x exposes {@link Loader#loadPDF(byte[])} as the entry
 * point — the older {@code PDDocument.load(byte[])} static helper was
 * deprecated and now delegates here. We always close the document
 * (try-with-resources) so the temp file backing the in-memory doc
 * gets released promptly.
 *
 * <p>For image-only / scanned PDFs this returns whatever
 * {@link PDFTextStripper} can pull, which is likely empty. That's
 * intentional — the orchestrator falls back to "skip with WARN" when
 * the extracted text is empty rather than spending tokens on a blank
 * prompt.
 */
@Component
public class PdfTextExtractor {

    /**
     * Extract plain text from the given PDF bytes. Returns an empty
     * string (never null) when the PDF yields no text. Throws on
     * malformed PDFs / IO failures — the caller decides whether to
     * mark the ticket as failed or skip it.
     */
    public String extract(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return "";
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            return new PDFTextStripper().getText(doc);
        }
    }
}
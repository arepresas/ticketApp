package com.ticketapp.minimaxai;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Extracts plain text from a digital-born PDF and rasterizes scanned
 * PDFs to PNG.
 *
 * <p>Used by the AI extraction pipeline (ADR 0006, D3): MiniMax's
 * chat-completions endpoint accepts images and videos but not PDFs,
 * so PDF receipts must be reduced to a transport the API actually
 * accepts before they can be sent.
 *
 * <p>PDFBox 3.x exposes {@link Loader#loadPDF(byte[])} as the entry
 * point — the older {@code PDDocument.load(byte[])} static helper was
 * deprecated and now delegates here. We always close the document
 * (try-with-resources) so the temp file backing the in-memory doc
 * gets released promptly.
 *
 * <p>For image-only / scanned PDFs, {@link #extract(byte[])} returns
 * whatever {@link PDFTextStripper} can pull (likely empty). The
 * orchestrator treats that as the trigger to rasterize the first
 * page via {@link #rasterizeFirstPageAsPng(byte[])} and feed the PNG
 * to the model as a normal image upload.
 */
@Component
@Slf4j
public class PdfTextExtractor {

    /**
     * DPI for the rasterized PNG. 200 is the sweet spot for receipt
     * OCR: high enough to keep small print and barcodes legible,
     * low enough to stay well under MiniMax's image-size limit
     * (the provider returns HTTP 400 for over-large payloads
     * without telling us what the limit is — see the
     * {@code minimax-ai} 2026-07-14 incident where 72 DPI scans
     * got rejected as too small).
     */
    static final int RASTERIZE_DPI = 200;

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

    /**
     * Render the first page of a PDF as PNG bytes. Returns
     * {@code null} (not an empty array) when the input is empty
     * or the PDF has zero pages — the caller uses null to
     * distinguish "nothing to rasterize" from "rasterized a blank
     * page" and surfaces a clearer error message in the empty
     * case.
     *
     * <p>Multi-page PDFs are reduced to the first page. Receipts
     * are single-page in practice; a multi-page upload is logged
     * at WARN so operators can spot a wrong-file upload without
     * breaking the flow.
     */
    public byte[] rasterizeFirstPageAsPng(byte[] pdfBytes) throws IOException {
        if (pdfBytes == null || pdfBytes.length == 0) {
            return null;
        }
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            int pageCount = doc.getNumberOfPages();
            if (pageCount == 0) {
                return null;
            }
            if (pageCount > 1) {
                log.warn("PDF has {} pages; rasterizing only the first page "
                        + "— multi-page receipts are out of scope", pageCount);
            }
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(
                    0, RASTERIZE_DPI);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IOException("no PNG ImageIO writer registered");
            }
            return out.toByteArray();
        }
    }
}
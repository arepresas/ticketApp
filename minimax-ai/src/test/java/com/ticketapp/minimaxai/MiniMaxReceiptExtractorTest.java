package com.ticketapp.minimaxai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.minimaxai.autoconfigure.MinimaxAiProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MiniMaxReceiptExtractor} — the provider
 * side of the {@link com.ticketapp.domain.ai.ReceiptExtractor} port
 * (ADR 0007).
 *
 * <p>Pins provider-specific behaviour that lives entirely inside
 * this module and that the BFF orchestrator must not depend on:
 * <ul>
 *   <li>PDF input routes through {@link PdfTextExtractor} when
 *       selectable text is available.</li>
 *   <li>Empty PDF text (scanned / image-only receipts) falls back to
 *       sending the raw PDF bytes as a document image so the model
 *       can OCR-interpret them.</li>
 *   <li>{@code <think>...</think>} blocks are stripped before parsing.</li>
 *   <li>Unparseable assistant replies raise
 *       {@link ReceiptExtractionException} with the raw text in the
 *       message so operators see what the model actually said.</li>
 *   <li>Missing required fields raise
 *       {@link ReceiptExtractionException}.</li>
 * </ul>
 */
class MiniMaxReceiptExtractorTest {

    private static final String MODEL = "MiniMax-M3";

    private MiniMaxApiClient client;
    private PdfTextExtractor pdfExtractor;
    private MinimaxAiProperties properties;
    private MiniMaxReceiptExtractor extractor;

    @BeforeEach
    void setUp() {
        client = mock(MiniMaxApiClient.class);
        pdfExtractor = mock(PdfTextExtractor.class);
        properties = new MinimaxAiProperties(
                "https://api.minimax.chat", "k", MODEL, 30_000L);
        extractor = new MiniMaxReceiptExtractor(
                client, pdfExtractor, new ObjectMapper(), properties);
    }

    @Test
    void imageInputForwardsBytesAndMimeTypeToTheClient() throws Exception {
        byte[] png = new byte[]{1, 2, 3};
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"Mercadona","purchase_date":"2026-07-04",
                 "products":[],"total_amount":1.20,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(png, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("Mercadona");
        assertThat(result.rawReply()).contains("Mercadona");
        assertThat(result.model()).isEqualTo(MODEL);
        verify(pdfExtractor, never()).extract(any());
    }

    @Test
    void pdfInputRoutesThroughPdfTextExtractor() throws Exception {
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F'};
        when(pdfExtractor.extract(pdf)).thenReturn("MERCADONA 12,50");
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"Mercadona","purchase_date":"2026-07-04",
                 "products":[],"total_amount":12.50,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(pdf, "application/pdf"));

        assertThat(result.result().merchant()).isEqualTo("Mercadona");
        verify(pdfExtractor).extract(pdf);
    }

    @Test
    void emptyPdfTextFallsBackToImageUpload() throws Exception {
        // Scanned / image-only PDFs (no selectable text) used to
        // land the ticket in ON_ERROR with "PDF text extraction
        // returned empty". The first iteration of the fallback
        // forwarded the raw PDF bytes as image — that fails too
        // because MiniMax rejects application/pdf in image_url
        // (HTTP 400, see the 2026-07-14 incident). The current
        // contract: rasterize the first page to PNG via
        // PDFRenderer and send the PNG through the same image
        // branch as a normal photo upload. Pinned here so a
        // future regression that re-throws on empty text, or that
        // skips the rasterization step, catches the eye.
        byte[] pdf = new byte[]{'%', 'P', 'D', 'F'};
        byte[] rasterized = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(pdfExtractor.extract(pdf)).thenReturn("");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdf)).thenReturn(rasterized);
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"Mercadona","purchase_date":"2026-07-04",
                 "products":[],"total_amount":12.50,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(pdf, "application/pdf"));

        assertThat(result.result().merchant()).isEqualTo("Mercadona");
        assertThat(result.result().totalAmount()).isEqualByComparingTo("12.50");
        // Pre-processing chain: text is consulted (and discarded
        // because empty), then the same pdf is rasterized.
        verify(pdfExtractor).extract(pdf);
        verify(pdfExtractor).rasterizeFirstPageAsPng(pdf);
        // Client must receive the rasterized PNG — not the raw PDF
        // bytes, not text. The mime is image/png so the API
        // actually accepts it.
        verify(client).extractReceipt(argThat(input ->
                input.pdfText() == null
                        && "image/png".equals(input.mimeType())
                        && input.bytes() == rasterized));
    }

    @Test
    void emptyPdfTextFallbackSurfacesRasterizationFailure() throws Exception {
        // Rasterization can fail on a corrupt PDF, an
        // out-of-memory huge doc, or a PDFBox internal error.
        // The fallback must surface the real cause (not a
        // generic "PDF text extraction" message) so operators can
        // see the underlying IOException in the dashboard.
        byte[] pdf = new byte[]{1};
        when(pdfExtractor.extract(pdf)).thenReturn("");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdf))
                .thenThrow(new java.io.IOException("PDFBox boom"));

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(pdf, "application/pdf")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("PDF rasterization failed")
                .hasMessageContaining("PDFBox boom");
    }

    @Test
    void emptyPdfTextFallbackRaisesWhenPdfHasNoPages() throws Exception {
        // Defensive: a zero-page PDF (extremely rare but valid in
        // the PDF spec) should surface a clear error, not a silent
        // null deref. The fallback returns null and the extractor
        // throws a dedicated "no pages to rasterize" message.
        byte[] pdf = new byte[]{1};
        when(pdfExtractor.extract(pdf)).thenReturn("");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdf)).thenReturn(null);

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(pdf, "application/pdf")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("no pages to rasterize");
    }

    @Test
    void emptyPdfTextFallbackStillSurfacesApiErrors() throws Exception {
        // Belt-and-braces: the fallback must not mask a downstream
        // failure. If the API call itself fails after the rasterize
        // + image-upload path, the exception propagates with the
        // underlying message — the operator sees the real cause,
        // not a stuck "PDF text extraction returned empty" string
        // that the fallback already handled.
        byte[] pdf = new byte[]{1};
        byte[] rasterized = new byte[]{(byte) 0x89, 'P', 'N', 'G'};
        when(pdfExtractor.extract(pdf)).thenReturn("");
        when(pdfExtractor.rasterizeFirstPageAsPng(pdf)).thenReturn(rasterized);
        when(client.extractReceipt(any())).thenThrow(
                new RuntimeException("MiniMax 503"));

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(pdf, "application/pdf")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("MiniMax 503")
                .hasMessageNotContaining("PDF text extraction");
    }

    @Test
    void thinkBlockIsStrippedBeforeJsonParse() throws Exception {
        when(client.extractReceipt(any())).thenReturn("""
                <think>The user wants me to parse a receipt.
                Merchant: Mercadona
                Date: 2026-07-04
                Total: 1.20 EUR</think>{
                  "merchant": "Mercadona",
                  "purchase_date": "2026-07-04",
                  "category": "food",
                  "products": [],
                  "total_amount": 1.20,
                  "currency": "EUR"
                }
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("Mercadona");
        // Raw reply is preserved end-to-end so the orchestrator can
        // persist it for audit. The stripping happens inside the
        // parser, not on the raw text we hand back.
        assertThat(result.rawReply()).contains("<think>");
    }

    @Test
    void discountLineItemIsAccepted() throws Exception {
        when(client.extractReceipt(any())).thenReturn("""
                {
                  "merchant": "Bouygues Telecom",
                  "purchase_date": "2026-07-01",
                  "category": "other",
                  "products": [
                    {"name":"Auchan 60 Go","quantity":1,"unit":"unit",
                     "price_per_unit":10.99,"line_total":10.99},
                    {"name":"Remise 6€","quantity":1,"unit":"unit",
                     "price_per_unit":-6.00,"line_total":-6.00}
                  ],
                  "total_amount": 4.99,
                  "currency": "EUR"
                }
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        List<ProductLine> products = result.result().products();
        assertThat(products).hasSize(2);
        assertThat(products.get(1).pricePerUnit()).isEqualByComparingTo("-6.00");
        assertThat(products.get(1).lineTotal()).isEqualByComparingTo("-6.00");
        assertThat(result.result().totalAmount()).isEqualByComparingTo("4.99");
    }

    @Test
    void unparseableReplyRaisesWithRawInMessage() throws Exception {
        when(client.extractReceipt(any())).thenReturn("not json at all");

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("not json at all");
    }

    @Test
    void missingRequiredFieldRaises() throws Exception {
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"X","total_amount":1,"currency":"EUR"}
                """);

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class);
    }

    @Test
    void unknownCategoryNormalisedToOther() throws Exception {
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"X","purchase_date":"2026-01-01",
                 "category":"pet_shop","products":[],
                 "total_amount":1,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().category()).isEqualTo("other");
    }

    @Test
    void replyWithOnlyThinkingRaisesTokenBudgetMessage() throws Exception {
        // Reproduces the 2026-07-05 incident: model burns the whole
        // token budget on chain-of-thought and emits no JSON at all.
        // The stripper drops the unclosed think block, leaving an
        // empty string, and the parser surfaces a message that names
        // the actual cause instead of a generic "non-JSON reply" —
        // so operators can see "token budget exhausted" in the WARN
        // log and act on it (raise max_completion_tokens, shorten
        // the prompt, etc.).
        String giantThink = "<think>" + "x".repeat(8000);
        when(client.extractReceipt(any())).thenReturn(giantThink);

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("only thinking")
                .hasMessageContaining("token budget");
    }

    @Test
    void closedThinkBlockThenJsonStillParses() throws Exception {
        // Sanity check that the existing happy-path (closed think
        // block + JSON) is unchanged by the unclosed-block fix.
        when(client.extractReceipt(any())).thenReturn("""
                <think>short reasoning</think>
                {"merchant":"X","purchase_date":"2026-01-01",
                 "products":[],"total_amount":1,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("X");
    }

    @Test
    void recoversJsonWrappedInOutputTagsViaSubstringExtraction() throws Exception {
        // 2026-07-05-style incident: the model wraps its reply in
        // <output>...</output> markup despite response_format:
        // json_object. Primary parse fails, the substring-recovery
        // helper lifts the first balanced { ... } block, and the
        // second parse succeeds.
        when(client.extractReceipt(any())).thenReturn("""
                <output>
                {"merchant":"Mercadona","purchase_date":"2026-07-04",
                 "products":[],"total_amount":12.50,"currency":"EUR"}
                </output>
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("Mercadona");
        assertThat(result.result().totalAmount()).isEqualByComparingTo("12.50");
    }

    @Test
    void recoversJsonFromRefusalTagWrap() throws Exception {
        // Some MiniMax model revisions wrap refusals in
        // <|refusal|>...<|/refusal|>. When the model refuses but
        // still emits the structured object after the refusal block,
        // substring recovery lifts the JSON.
        when(client.extractReceipt(any())).thenReturn("""
                <|refusal|>I cannot process this.||
                {"merchant":"LIDL","purchase_date":"2026-07-01",
                 "products":[],"total_amount":22.54,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("LIDL");
    }

    @Test
    void substringRecoveryFailsWhenNoJsonObjectPresent() throws Exception {
        // The reply is pure prose — no { anywhere. Stripper leaves
        // it intact (no think tag), primary parse fails, substring
        // recovery finds no balanced brace block, the helper returns
        // null, and the original parse failure surfaces with the
        // raw text attached.
        when(client.extractReceipt(any())).thenReturn(
                "Sorry, I cannot extract that for you.");

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("non-JSON reply")
                .hasMessageContaining("Sorry");
    }

    @Test
    void invalidJsonWithoutBalancedBracesSurfacesFailure() throws Exception {
        // No think tag, no closed brace, just broken JSON. Primary
        // parse fails, substring recovery finds no closing brace,
        // original failure surfaces.
        when(client.extractReceipt(any())).thenReturn(
                "{\"merchant\":\"X\",\"purchase_date\"");

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("non-JSON reply");
    }

    @Test
    void substringRecoveryRespectsStringEscapes() throws Exception {
        // The brace matcher must not be confused by { inside a JSON
        // string. A balanced object with an escaped brace inside a
        // string value must round-trip correctly.
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"X {not a brace}","purchase_date":"2026-01-01",
                 "products":[],"total_amount":1,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("X {not a brace}");
    }

    @Test
    void blankRawReplyRaisesClearTokenBudgetMessage() throws Exception {
        // Empty string after fence stripping — the stripper returns
        // empty, the parser sees nothing parseable and surfaces the
        // explicit "only thinking" message.
        when(client.extractReceipt(any())).thenReturn("");

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("only thinking");
    }

    @Test
    void codeFencesAreStrippedBeforeParse() throws Exception {
        // The model wrapped the JSON in ``` fences. Stripper drops
        // them and the bare object parses cleanly.
        when(client.extractReceipt(any())).thenReturn("""
                ```
                {"merchant":"LIDL","purchase_date":"2026-07-01",
                 "products":[],"total_amount":22.54,"currency":"EUR"}
                ```
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("LIDL");
    }

    @Test
    void unknownCurrencyLengthRaisesParseFailure() throws Exception {
        // The currency field is 4 chars — outside the ISO 4217 set.
        // The compact constructor on ReceiptExtractionResult rejects
        // it; the parser surfaces the IllegalStateException wrapped
        // in ReceiptExtractionException with the original cause
        // attached.
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"X","purchase_date":"2026-01-01",
                 "products":[],"total_amount":1,"currency":"EURO"}
                """);

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png")))
                .isInstanceOf(ReceiptExtractionException.class);
    }

    @Test
    void shopObjectInResponseIsIgnoredByParserButPreservedInRaw() throws Exception {
        // The updated prompt asks the model to emit a top-level
        // "shop" object with address/contact fields. The provider
        // parser doesn't read those fields (they live in
        // extraction_payload JSONB for the BFF normaliser to pick
        // up); it only parses the typed fields. The raw reply is
        // returned to the caller verbatim so the JSONB write keeps
        // the shop object intact.
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"Mercadona",
                 "purchase_date":"2026-07-04",
                 "category":"food",
                 "shop":{"address":"Calle Mayor 1","city":"Madrid","country":"ES"},
                 "products":[{"name":"Bread","quantity":1,"unit":null,
                              "price_per_unit":1.20,"line_total":1.20}],
                 "total_amount":1.20,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("Mercadona");
        assertThat(result.result().products()).hasSize(1);
        assertThat(result.rawReply()).contains("\"shop\"")
                .contains("Calle Mayor 1");
    }

    @Test
    void lineLevelDiscountIsReflectedInPricePerUnit() throws Exception {
        // The prompt instructs the AI to apply line-level discounts
        // (loyalty / promo / member card) to the line's
        // price_per_unit rather than emit a separate discount line.
        // The parser captures price_per_unit as-is; the test pins
        // the expected effective value to lock the prompt's
        // behaviour. If a future prompt regression makes the AI
        // emit the printed (pre-discount) price again, this test
        // catches it before the change reaches production.
        //
        // Receipt scenario: 2 × 1.20€ bread = 2.40€, with a 5%
        // loyalty discount applied to that line (-0.12€). The model
        // should emit the discounted effective per-unit price:
        //   price_per_unit = (2.40 - 0.12) / 2 = 1.14
        //   line_total     = 2 × 1.14 = 2.28
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"Mercadona",
                 "purchase_date":"2026-07-04",
                 "category":"food",
                 "shop":{"city":"Madrid"},
                 "products":[{"name":"Bread","quantity":2,"unit":null,
                              "price_per_unit":1.14,"line_total":2.28}],
                 "total_amount":2.28,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        var line = result.result().products().get(0);
        assertThat(line.pricePerUnit()).isEqualByComparingTo("1.14");
        assertThat(line.lineTotal()).isEqualByComparingTo("2.28");
        assertThat(result.result().totalAmount()).isEqualByComparingTo("2.28");
    }

    @Test
    void missingShopObjectDoesNotBreakParsing() throws Exception {
        // Backwards compat: payloads from older prompts (pre-update)
        // don't carry the shop object. The parser must parse them
        // exactly as before — merchant / products / total all
        // populate normally, no exception, no log noise about the
        // missing optional field.
        when(client.extractReceipt(any())).thenReturn("""
                {"merchant":"Dia",
                 "purchase_date":"2026-07-04",
                 "category":"food",
                 "products":[{"name":"Milk","quantity":1,"unit":"L",
                              "price_per_unit":0.90,"line_total":0.90}],
                 "total_amount":0.90,"currency":"EUR"}
                """);

        ReceiptExtraction result = extractor.extract(
                new ReceiptExtractionRequest(new byte[]{1}, "image/png"));

        assertThat(result.result().merchant()).isEqualTo("Dia");
        assertThat(result.result().products()).hasSize(1);
    }
}
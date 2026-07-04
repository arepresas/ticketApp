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
 *   <li>PDF input routes through {@link PdfTextExtractor}.</li>
 *   <li>Empty PDF text is treated as a failure.</li>
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
    void emptyPdfTextIsTreatedAsFailure() throws Exception {
        byte[] pdf = new byte[]{1};
        when(pdfExtractor.extract(pdf)).thenReturn("");

        assertThatThrownBy(() -> extractor.extract(
                new ReceiptExtractionRequest(pdf, "application/pdf")))
                .isInstanceOf(ReceiptExtractionException.class)
                .hasMessageContaining("PDF text extraction");
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
}
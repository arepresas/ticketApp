package com.ticketapp.minimaxai;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.ResponseFormatJsonObject;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionContentPart;
import com.openai.models.chat.completions.ChatCompletionContentPartImage;
import com.openai.models.chat.completions.ChatCompletionContentPartText;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;
import lombok.experimental.Accessors;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Client for the MiniMax chat-completions endpoint, built on the
 * official OpenAI Java SDK.
 *
 * <p>MiniMax exposes its chat-completions surface as
 * OpenAI-compatible (ADR 0006, D6) — see
 * <a href="https://docs.minimax.io/api-reference/text-chat-openai">the
 * MiniMax API reference</a>. The SDK accepts a custom
 * {@code baseUrl} so we point it at MiniMax and the request/response
 * format is the standard OpenAI shape (system + user messages,
 * image_url content parts, model id).
 *
 * <p>Why the SDK over a hand-rolled HTTP client? The SDK gives us
 * typed request/response objects, automatic retry on transient
 * failures, and the prompt-caching / streaming knobs that the
 * MiniMax docs document — none of which we want to re-implement.
 *
 * <p>Privacy: the SDK does not log request or response bodies by
 * default and we don't enable its logging layer. Logging the receipt
 * content (merchant names, prices, items) is forbidden by the
 * security posture — see ADR 0006 "Privacy" consequences.
 */
@Component
@RequiredArgsConstructor
public final class MiniMaxApiClient {

    private final OpenAIClient client;

    /**
     * Factory. Builds an OpenAI-compatible SDK client pointed at
     * MiniMax with the operator's credentials and wraps it. Used by
     * production wiring (the autoconfig calls {@link #create} in
     * its {@code @Bean} method) and by tests that want a real-shaped
     * SDK client.
     *
     * <p>{@code apiKey} must be non-blank and not the dev
     * placeholder — the {@link #requireKey} check fails fast at
     * boot so the scheduler doesn't accidentally run with a
     * missing credential.
     */
    public static MiniMaxApiClient create(String baseUrl,
                                          String apiKey,
                                          String model,
                                          Duration timeout) {
        Objects.requireNonNull(model, "model");
        requireKey(apiKey);
        OpenAIClient client = OpenAIOkHttpClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .timeout(timeout)
                .build();
        return new MiniMaxApiClient(client);
    }

    /**
     * Ask MiniMax to extract structured receipt data from the given
     * input. The {@code input} is either an image (the {@code bytes}
     * + {@code mimeType} pair) or plain text already extracted from
     * a PDF ({@code pdfText} set, image fields null).
     *
     * @return raw assistant text. The caller is responsible for
     *         parsing it as JSON — the API doesn't enforce JSON mode
     *         so we keep the parsing layer thin and observable.
     */
    public String extractReceipt(ReceiptInput input) throws java.io.IOException {
        ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                .model(input.model())
                .addSystemMessage(EXTRACTION_PROMPT)
                .temperature(0.0)      // deterministic JSON
                // 16384 covers complex receipts (~15 products with
                // multiple discount lines each) on MiniMax-M3, which
                // is a thinking-style model — it burns a non-trivial
                // share of its budget on chain-of-thought before
                // emitting JSON. The upstream default (~1024) cuts
                // off mid-JSON. 4096 was too tight (2026-07-05
                // incident — receipt c57f2dc9...). 8192 was the
                // previous default and handles ~10 products
                // comfortably. Bumping to 16384 covers the worst
                // observed case (Lidl receipt, 7+ products with
                // per-line loyalty discounts, 2026-07-13) where
                // 8192 was exhausted in the thinking block alone.
                // Dial down if per-ticket cost becomes a concern,
                // once the prompt stops producing long reasoning.
                .maxCompletionTokens(16384)
                // Constrain the model to emit a JSON object. Without
                // this, MiniMax-M3 occasionally wraps the reply in
                // markup (<output>...</output>, <|refusal|>...) which
                // breaks the downstream parser. See WARN log
                // "Extraction failed ... Unexpected character '<'" in
                // 2026-07-05 incident.
                .responseFormat(ResponseFormatJsonObject.builder().build());
        if (input.pdfText() != null) {
            params.addUserMessageOfArrayOfContentParts(
                    List.of(textPart("Receipt text (extracted from PDF):\n\n" + input.pdfText())));
        } else {
            params.addUserMessageOfArrayOfContentParts(
                    List.of(imagePart(input.bytes(), input.mimeType())));
        }

        // Use withRawResponse() so we can read the body on every
        // failure path. Critical: read the body as bytes FIRST, then
        // attempt to parse it — that way a parse failure
        // (OpenAIInvalidDataException when Jackson chokes on non-JSON)
        // doesn't lose the raw response. The SDK's parse() reads
        // the body stream and closes it; if the bytes are bad we
        // have nothing to log without doing it ourselves.
        com.openai.core.http.HttpResponseFor<ChatCompletion> resp;
        try {
            resp = client.chat().completions().withRawResponse().create(params.build());
        } catch (OpenAIServiceException e) {
            // The SDK throws a typed exception per status: 401, 429,
            // 500, etc. All extend OpenAIServiceException. The
            // exception already carries the response body for non-2xx
            // — we surface it in the WARN log.
            throw new MiniMaxApiException(e.statusCode(),
                    "MiniMax returned " + e.statusCode() + ": " + bodyAsString(e));
        } catch (RuntimeException e) {
            // Connection refused / DNS / timeout — no response at all.
            throw new MiniMaxApiException(0,
                    "MiniMax call failed (" + e.getClass().getSimpleName() + "): " + e.getMessage());
        }
        try {
            int status = resp.statusCode();
            byte[] bodyBytes = readAllBytes(resp);
            String bodyText = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            if (status / 100 != 2) {
                throw new MiniMaxApiException(status,
                        "MiniMax returned " + status + ": " + truncate(bodyText));
            }
            // 2xx path — parse manually with the SDK's mapper so we
            // own the bytes and the typed object alike.
            ChatCompletion completion;
            try {
                completion = com.openai.core.ObjectMappers.jsonMapper()
                        .readValue(bodyBytes, ChatCompletion.class);
            } catch (Exception parseError) {
                throw new MiniMaxApiException(status,
                        "MiniMax returned " + status + " but the body is not valid JSON: "
                                + truncate(bodyText) + " | parse error: " + parseError.getMessage());
            }
            return extractAssistantText(completion);
        } finally {
            resp.close();
        }
    }

    private static byte[] readAllBytes(com.openai.core.http.HttpResponseFor<?> resp) throws java.io.IOException {
        try (java.io.InputStream in = resp.body()) {
            return in.readAllBytes();
        }
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() > 1024 ? s.substring(0, 1024) + "...[truncated]" : s;
    }

    private static String bodyAsString(OpenAIServiceException e) {
        String body = Optional.ofNullable(e.body())
                .map(JsonValue::toString)
                .orElse("");
        if (body.length() > 1024) {
            body = body.substring(0, 1024) + "...[truncated]";
        }
        return body;
    }

    private static String extractAssistantText(ChatCompletion completion) {
        if (completion.choices() == null || completion.choices().isEmpty()) {
            throw new MiniMaxApiException(502, "MiniMax returned no choices");
        }
        return completion.choices().get(0)
                .message()
                .content()
                .orElseThrow(() -> new MiniMaxApiException(502, "MiniMax returned empty content"));
    }

    private static ChatCompletionContentPart imagePart(byte[] imageBytes, String mimeType) {
        String dataUrl = "data:" + mimeType + ";base64,"
                + Base64.getEncoder().encodeToString(imageBytes);
        return ChatCompletionContentPart.ofImageUrl(
                ChatCompletionContentPartImage.builder()
                        .imageUrl(ChatCompletionContentPartImage.ImageUrl.builder()
                                .url(dataUrl)
                                .build())
                        .build());
    }

    private static ChatCompletionContentPart textPart(String text) {
        return ChatCompletionContentPart.ofText(
                ChatCompletionContentPartText.builder()
                        .text(text)
                        .build());
    }

    private static String requireKey(String key) {
        if (key == null || key.isBlank() || "dev-placeholder".equals(key)) {
            throw new IllegalArgumentException(
                    "MINIMAX_API_KEY is missing or set to the dev placeholder");
        }
        return key;
    }

    /** Input the extraction job feeds into the API call. */
    public record ReceiptInput(
            String model,
            byte[] bytes,
            String mimeType,
            String pdfText
    ) {
        public static ReceiptInput image(String model, byte[] bytes, String mimeType) {
            return new ReceiptInput(model, bytes, mimeType, null);
        }
        public static ReceiptInput pdfText(String model, String text) {
            return new ReceiptInput(model, null, null, text);
        }

        // Records auto-generate equals/hashCode/toString that treat
        // byte[] by reference — two ReceiptInputs with the same
        // image bytes would compare as different. Override so equality
        // reflects the byte content (mirrors Ticket#fileData and
        // ReceiptExtractionRequest#content).
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ReceiptInput other)) return false;
            return java.util.Objects.equals(model, other.model)
                    && java.util.Objects.equals(mimeType, other.mimeType)
                    && java.util.Objects.equals(pdfText, other.pdfText)
                    && java.util.Arrays.equals(bytes, other.bytes);
        }

        @Override
        public int hashCode() {
            int h = java.util.Objects.hash(model, mimeType, pdfText);
            return 31 * h + java.util.Arrays.hashCode(bytes);
        }

        @Override
        public String toString() {
            int byteCount = bytes == null ? 0 : bytes.length;
            return "ReceiptInput[model=" + model
                    + ", bytes=" + byteCount + " bytes"
                    + ", mimeType=" + mimeType
                    + ", pdfText=" + (pdfText == null ? "null"
                            : pdfText.length() + " chars") + "]";
        }
    }

    /** Wraps a non-2xx response so callers can decide how to handle it. */
    @Getter
    @ToString
    @EqualsAndHashCode(callSuper = false)
    @Accessors(fluent = true)
    public static final class MiniMaxApiException extends RuntimeException {
        private final int statusCode;
        public MiniMaxApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
    }

    /**
     * Static prompt kept on the client because it is part of the
     * wire contract with the model — changes here are observable in
     * any audit of the raw_response column. The schema is intentionally
     * verbose to minimise the chance the model returns a free-form
     * explanation instead of JSON.
     *
     * <p>The "no reasoning" clause at the top exists because
     * MiniMax-M3 (and other DeepSeek-style models exposed through the
     * OpenAI-compatible surface) emit {@code <think>...</think>}
     * chain-of-thought blocks even when {@code response_format:
     * json_object} is set. The block sits before the JSON object and
     * breaks naive parsers. Forbidding it explicitly + setting
     * {@code response_format} gives belt-and-braces protection. The
     * defensive {@code <think>} stripper in
     * {@link com.ticketapp.bff.ai.TicketExtractionService} is the
     * safety net for the cases the prompt doesn't catch.
     */
    public static final String EXTRACTION_PROMPT = """
            You are a receipt-parsing assistant.

            OUTPUT RULES — read first, comply strictly:
            1. The very first character of your reply MUST be '{'. No
               whitespace, no newline, no markdown, no XML, no
               commentary before it.
            2. Do NOT include any reasoning, chain-of-thought,
               analysis, or thinking blocks. In particular, do NOT
               emit a <think>...</think> block. CRITICAL: every
               token you spend inside a <think>...</think> block
               is a token you cannot spend on the JSON reply. On
               long receipts with many discounted lines the model
               runs out of budget mid-thinking and never emits any
               JSON, which makes the whole extraction fail. If you
               find yourself wanting to reason internally, do it
               silently and emit only the final JSON.
            3. Output ONLY the JSON object. No prose, no markdown
               fences (no ```), no trailing text, no "Here is the
               result:" preamble.
            4. If a field is unreadable, omit it (don't guess).

            Schema (return exactly this shape):

            {
              "merchant": string,                    // store name as printed on the receipt
              "purchase_date": "YYYY-MM-DD",         // ISO date of the purchase
              "category": "food|pharmacy|restaurant|fuel|other",
              "shop": {                              // optional — omit fields you can't read from the receipt
                "address": string,                   // street + number
                "postal_code": string,
                "city": string,
                "country": string,                   // ISO 3166-1 alpha-2 (ES, FR, PT...)
                "phone": string,
                "tax_id": string,                    // CIF (ES), SIRET (FR), VAT number, etc.
                "website": string
              },
              "products": [
                {
                  "name": string,
                  "quantity": number,
                  "unit": string,                    // e.g. "kg", "L", "unit"
                  "price_per_unit": number,          // EFFECTIVE per-unit price AFTER any line-level discount
                  "line_total": number               // EFFECTIVE line total = quantity * price_per_unit, rounded to 2 decimals
                }
              ],
              "total_amount": number,
              "currency": "EUR|USD|GBP|..."          // ISO 4217
            }

            Field rules:
            - quantity and prices are decimals, not strings.
            - price_per_unit MUST reflect the EFFECTIVE price the customer actually paid for that line — after any line-level discount is applied (loyalty card, member discount, promo, etc.). If a discount appears on the receipt as a separate line, attribute it to the products it applies to and reduce price_per_unit accordingly. DO NOT emit a separate discount line: apply discounts to the lines they belong to.
            - line_total must equal quantity * price_per_unit, rounded to 2 decimals.
            - The sum of every product's line_total must equal total_amount.
            - currency defaults to EUR if no symbol is visible.
            - shop fields: include only what you can read from the receipt header or footer (address printed at the top, phone/tax id printed at the bottom of a Spanish or French receipt). Omit any field you can't read; never guess.
            """;
}

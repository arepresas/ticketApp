package com.ticketapp.minimaxai;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.http.HttpResponseFor;
import com.openai.errors.OpenAIServiceException;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.services.blocking.ChatService;
import com.openai.services.blocking.chat.ChatCompletionService;
import com.ticketapp.minimaxai.MiniMaxApiClient.MiniMaxApiException;
import com.ticketapp.minimaxai.MiniMaxApiClient.ReceiptInput;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MiniMaxApiClient}. The {@link OpenAIClient} is
 * stubbed with Mockito so we never hit the network — these tests
 * pin the wire contract the orchestrator depends on, not the
 * upstream service.
 *
 * <p>The client uses {@code withRawResponse()} and reads the body
 * bytes itself so it can log the upstream's response on every
 * failure path (typed exceptions, parse errors, network issues).
 * Tests below cover all three.
 */
class MiniMaxApiClientTest {

    private OpenAIClient client;
    private ChatService chat;
    private ChatCompletionService completions;
    private ChatCompletionService.WithRawResponse completionsRaw;
    private MiniMaxApiClient api;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(OpenAIClient.class);
        chat = mock(ChatService.class);
        completions = mock(ChatCompletionService.class);
        completionsRaw = mock(ChatCompletionService.WithRawResponse.class);
        when(client.chat()).thenReturn(chat);
        when(chat.completions()).thenReturn(completions);
        when(completions.withRawResponse()).thenReturn(completionsRaw);
        // The autoconfig injects an OpenAIClient via Spring DI; tests
        // instantiate the wrapper directly with a Mockito stub.
        api = new MiniMaxApiClient(client);
    }

    @Test
    void imageInputSendsUserMessageWithImageUrlContentPart() throws Exception {
        stubOk("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"X\"},\"finish_reason\":\"stop\"}]}");
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

        api.extractReceipt(ReceiptInput.image("MiniMax-M3", png, "image/png"));

        ArgumentCaptor<ChatCompletionCreateParams> cap =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completionsRaw).create(cap.capture());
        ChatCompletionCreateParams params = cap.getValue();
        assertThat(params.model().toString()).isEqualTo("MiniMax-M3");

        var messages = params.messages();
        assertThat(messages).hasSize(2);
        var system = messages.get(0).system();
        assertThat(system).isPresent();
        assertThat(system.get().content().text()).isPresent();
        assertThat(system.get().content().text().get()).contains("receipt-parsing assistant");

        var user = messages.get(1).user();
        assertThat(user).isPresent();
        var userParts = user.get().content().arrayOfContentParts().get();
        assertThat(userParts).hasSize(1);
        var imagePart = userParts.get(0).imageUrl();
        assertThat(imagePart).isPresent();
        String url = imagePart.get().imageUrl().url();
        assertThat(url).startsWith("data:image/png;base64,");
        // base64 of those 8 PNG signature bytes
        assertThat(url).contains("iVBORw0KGgo");
    }

    @Test
    void pdfTextInputSendsTextPartNotImage() throws Exception {
        stubOk("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"X\"},\"finish_reason\":\"stop\"}]}");

        api.extractReceipt(ReceiptInput.pdfText("MiniMax-M3", "MERCADONA\nTotal: 25,28"));

        ArgumentCaptor<ChatCompletionCreateParams> cap =
                ArgumentCaptor.forClass(ChatCompletionCreateParams.class);
        verify(completionsRaw).create(cap.capture());
        var messages = cap.getValue().messages();
        assertThat(messages).hasSize(2);
        var user = messages.get(1).user();
        assertThat(user).isPresent();
        var userParts = user.get().content().arrayOfContentParts().get();
        assertThat(userParts).hasSize(1);
        var textPart = userParts.get(0).text();
        assertThat(textPart).isPresent();
        assertThat(textPart.get().text()).contains("MERCADONA");
    }

    @Test
    void returnsAssistantContentOn2xx() throws Exception {
        stubOk("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"{\\\"merchant\\\":\\\"X\\\"}\"},\"finish_reason\":\"stop\"}]}");

        String content = api.extractReceipt(ReceiptInput.pdfText("MiniMax-M3", "x"));

        assertThat(content).isEqualTo("{\"merchant\":\"X\"}");
    }

    @Test
    void throwsOn401WithBodyInMessage() throws Exception {
        OpenAIServiceException e = mock(OpenAIServiceException.class);
        when(e.statusCode()).thenReturn(401);
        when(e.body()).thenReturn(JsonValue.from("{\"message\":\"invalid api key\"}"));
        when(completionsRaw.create(any(ChatCompletionCreateParams.class))).thenThrow(e);

        assertThatThrownBy(() -> api.extractReceipt(ReceiptInput.pdfText("MiniMax-M3", "x")))
                .isInstanceOf(MiniMaxApiException.class)
                .hasMessageContaining("401")
                .hasMessageContaining("invalid api key");
    }

    @Test
    void throwsWithBodyWhenUpstreamReturnsHtml() throws Exception {
        // Simulate a proxy/CDN returning HTML on a 2xx status — the
        // typed exceptions don't fire, but Jackson chokes on parse.
        // We need the WARN log to surface the actual body so the
        // operator can tell "got an HTML landing page" from "the
        // API rejected our key".
        String html = "<!DOCTYPE html><html><body>404 Not Found</body></html>";
        HttpResponseFor<ChatCompletion> resp = mock(HttpResponseFor.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
        when(completionsRaw.create(any(ChatCompletionCreateParams.class))).thenReturn(resp);

        assertThatThrownBy(() -> api.extractReceipt(ReceiptInput.pdfText("MiniMax-M3", "x")))
                .isInstanceOf(MiniMaxApiException.class)
                .hasMessageContaining("404 Not Found")
                .hasMessageContaining("DOCTYPE");
    }

    @Test
    void throwsOnJsonParseFailure() throws Exception {
        // 2xx with truncated / malformed JSON — body has detail, parse fails.
        String malformed = "{\"choices\":[";
        HttpResponseFor<ChatCompletion> resp = mock(HttpResponseFor.class);
        when(resp.statusCode()).thenReturn(200);
        when(resp.body()).thenReturn(new ByteArrayInputStream(malformed.getBytes(StandardCharsets.UTF_8)));
        when(completionsRaw.create(any(ChatCompletionCreateParams.class))).thenReturn(resp);

        assertThatThrownBy(() -> api.extractReceipt(ReceiptInput.pdfText("MiniMax-M3", "x")))
                .isInstanceOf(MiniMaxApiException.class)
                .hasMessageContaining("not valid JSON")
                .hasMessageContaining("{\"choices\":[");
    }

    @Test
    void throwsOnUnexpectedRuntimeException() {
        when(completionsRaw.create(any(ChatCompletionCreateParams.class)))
                .thenThrow(new RuntimeException("connection reset"));

        assertThatThrownBy(() -> api.extractReceipt(ReceiptInput.pdfText("MiniMax-M3", "x")))
                .isInstanceOf(MiniMaxApiException.class)
                .hasMessageContaining("connection reset");
    }

    @Test
    void productionFactoryRejectsPlaceholderKey() {
        assertThatThrownBy(() -> MiniMaxApiClient.create(
                "https://api.minimax.io/v1", "dev-placeholder", "MiniMax-M3", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MINIMAX_API_KEY");
    }

    @Test
    void productionFactoryRejectsBlankKey() {
        assertThatThrownBy(() -> MiniMaxApiClient.create(
                "https://api.minimax.io/v1", "  ", "MiniMax-M3", Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void receiptInputImageFactoryCarriesImageFields() {
        byte[] bytes = new byte[]{1, 2, 3};
        ReceiptInput input = ReceiptInput.image("MiniMax-M3", bytes, "image/png");

        assertThat(input.model()).isEqualTo("MiniMax-M3");
        assertThat(input.bytes()).isEqualTo(bytes);
        assertThat(input.mimeType()).isEqualTo("image/png");
        assertThat(input.pdfText()).isNull();
    }

    @Test
    void receiptInputPdfTextFactoryCarriesTextFields() {
        ReceiptInput input = ReceiptInput.pdfText("MiniMax-M3", "MERCADONA\nTotal: 12.50");

        assertThat(input.model()).isEqualTo("MiniMax-M3");
        assertThat(input.bytes()).isNull();
        assertThat(input.mimeType()).isNull();
        assertThat(input.pdfText()).isEqualTo("MERCADONA\nTotal: 12.50");
    }

    @Test
    void receiptInputEqualsTreatsByteArrayContentBased() {
        // The override matters: two ReceiptInputs with the same
        // image bytes must compare as equal even when constructed
        // independently.
        byte[] bytes = new byte[]{1, 2, 3};
        ReceiptInput a = ReceiptInput.image("MiniMax-M3", bytes, "image/png");
        ReceiptInput b = ReceiptInput.image("MiniMax-M3", bytes.clone(), "image/png");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void receiptInputEqualsDistinguishesDifferentBytes() {
        ReceiptInput a = ReceiptInput.image("MiniMax-M3", new byte[]{1, 2, 3}, "image/png");
        ReceiptInput b = ReceiptInput.image("MiniMax-M3", new byte[]{4, 5, 6}, "image/png");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void receiptInputEqualsDistinguishesImageFromPdfText() {
        // Image vs PDF text are mutually exclusive — verify the
        // override distinguishes them (otherwise a record carrying
        // pdfText would compare as equal to one carrying image bytes,
        // since both would have non-null content fields).
        ReceiptInput image = ReceiptInput.image("MiniMax-M3", new byte[]{1, 2, 3}, "image/png");
        ReceiptInput text = ReceiptInput.pdfText("MiniMax-M3", "MERCADONA");

        assertThat(image).isNotEqualTo(text);
        assertThat(text).isNotEqualTo(image);
    }

    @Test
    void receiptInputToStringShowsByteCountNotRawBytes() {
        // Critical: the bytes may contain a receipt image — toString
        // must not dump them into a log line.
        ReceiptInput input = ReceiptInput.image("MiniMax-M3",
                new byte[]{(byte) 0x89, 'P', 'N', 'G'}, "image/png");

        String rendered = input.toString();
        assertThat(rendered).contains("4 bytes").contains("image/png").contains("MiniMax-M3");
        assertThat(rendered).doesNotContain("89"); // PNG header byte must not leak
    }

    @Test
    void receiptInputToStringShowsPdfTextLengthNotContent() {
        // pdfText may carry the full extracted receipt text — that
        // could be hundreds of lines. toString must report the size
        // (so an operator knows there's a payload) without dumping
        // the content into a log line.
        ReceiptInput input = ReceiptInput.pdfText("MiniMax-M3", "MERCADONA\nTotal: 12.50");

        String rendered = input.toString();
        assertThat(rendered).contains("22 chars"); // length of "MERCADONA\nTotal: 12.50"
        assertThat(rendered).doesNotContain("MERCADONA");
        assertThat(rendered).doesNotContain("Total: 12.50");
    }

    // ---- helpers ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubOk(String body) throws Exception {
        HttpResponseFor<ChatCompletion> response = mock(HttpResponseFor.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        // The production code does NOT call parse() — it reads the
        // body bytes and uses the SDK's mapper directly. We still
        // need parse() configured in case any other code path hits
        // it; stubbing it to return null is fine because production
        // doesn't invoke it.
        when(response.parse()).thenReturn(null);
        when(completionsRaw.create(any(ChatCompletionCreateParams.class))).thenReturn(response);
    }
}
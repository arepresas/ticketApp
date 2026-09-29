package com.ticketapp.bff.ai;

import com.ticketapp.domain.ai.DocumentTextExtractor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reusable test double for {@link DocumentTextExtractor} — the port
 * behind the upload-time OCR step.
 *
 * <p><b>Why this exists separately from the receipt-extraction fake.</b>
 * {@code TestReceiptExtractorConfig} covers the scheduled extraction
 * port, but nothing covered the OCR port. The production bean (from
 * {@code OpenAiAutoConfiguration}) therefore stayed wired during
 * {@code *IT} runs, and since the upload path used to ignore
 * {@code ticketapp.ai.enabled}, any IT that posted a file with bytes
 * issued a real paid request to the provider endpoint — a direct
 * violation of ADR 0006 D7 ("no test ever hits the API").
 *
 * <p><b>Why auto-registered instead of {@code @Import}-ed.</b> The
 * suite uploads files from more than one IT and a future IT would
 * have to remember this import. Listing the class in
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * under {@code src/test/resources} makes every Spring test in the
 * module hermetic by construction rather than by remembering.
 *
 * <p>Marked {@code @Primary} so Spring prefers it over the
 * production bean, which stays defined but unused.
 */
@AutoConfiguration
public class TestDocumentTextExtractorConfig {

    /** Content types of every call, so an IT can assert the port
     *  was never reached (the kill-switch contract). */
    private static final List<String> CALLS = new ArrayList<>();

    /** Text the next call returns; {@code null} simulates a
     *  provider that transcribed nothing. */
    private static final AtomicReference<String> NEXT_TEXT =
            new AtomicReference<>("");

    public static void reset() {
        CALLS.clear();
        NEXT_TEXT.set("");
    }

    public static List<String> calls() {
        return List.copyOf(CALLS);
    }

    /** Queue the text the next call returns. */
    public static void willReturn(String text) {
        NEXT_TEXT.set(text);
    }

    @Bean
    @Primary
    public DocumentTextExtractor testDocumentTextExtractor() {
        return (bytes, contentType) -> {
            CALLS.add(contentType);
            return NEXT_TEXT.get();
        };
    }
}

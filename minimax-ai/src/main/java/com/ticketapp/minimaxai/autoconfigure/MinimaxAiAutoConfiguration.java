package com.ticketapp.minimaxai.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.ticketapp.minimaxai.MiniMaxApiClient;
import com.ticketapp.minimaxai.MiniMaxDocumentTextExtractor;
import com.ticketapp.minimaxai.MiniMaxReceiptExtractor;
import com.ticketapp.minimaxai.PdfTextExtractor;
import com.ticketapp.minimaxai.ReceiptResponseParser;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.time.Duration;

/**
 * Spring Boot autoconfiguration that registers the MiniMax-backed
 * {@link com.ticketapp.domain.ai.ReceiptExtractor} bean (ADR 0007).
 *
 * <p>Picked up automatically by Spring Boot 4 via the import in
 * {@code META-INF/spring/...AutoConfiguration.imports}. The BFF
 * module never references a MiniMax class directly — it just
 * autowires the {@code ReceiptExtractor} port and gets the bean
 * from whichever AI module is on the classpath.
 *
 * <p>One explicit {@code @Bean} per capability (no component scan):
 * a future provider module copies this shape with its own beans,
 * and two providers on the classpath fail with duplicate-bean
 * instead of silent ambiguity.
 */
@AutoConfiguration
@EnableConfigurationProperties(MinimaxAiProperties.class)
@Slf4j
public class MinimaxAiAutoConfiguration {

    /**
     * The OpenAI-compatible HTTP client. Built from the operator's
     * {@code baseUrl}, {@code apiKey}, and {@code timeoutMs}. The
     * OpenAI Java SDK does not ship its own Spring autoconfig, so we
     * declare this here.
     *
     * <p>The INFO log on boot is intentional: when the
     * {@code baseUrl} is misconfigured (missing env var → empty
     * string → SDK falls through to its hardcoded OpenAI default)
     * every subsequent extraction returns a 401 from
     * {@code api.openai.com} instead of the actual provider. Logging
     * the resolved URL on boot gives the operator the answer in one
     * place rather than chasing logs.
     *
     * <p>Fails fast on a missing key so the scheduler doesn't run
     * with a credential that can never succeed.
     */
    @Bean
    public OpenAIClient openAIClient(MinimaxAiProperties properties) {
        requireKey(properties.apiKey());
        log.info("MiniMax OpenAI client configured: baseUrl={} model={} timeoutMs={}",
                properties.baseUrl(), properties.model(), properties.timeoutMs());
        return OpenAIOkHttpClient.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
    }

    @Bean
    public MiniMaxApiClient miniMaxApiClient(OpenAIClient client, MinimaxAiProperties properties) {
        return new MiniMaxApiClient(client, properties);
    }

    @Bean
    public PdfTextExtractor pdfTextExtractor() {
        return new PdfTextExtractor();
    }

    @Bean
    public ReceiptResponseParser receiptResponseParser(ObjectMapper objectMapper) {
        return new ReceiptResponseParser(objectMapper);
    }

    @Bean
    public MiniMaxReceiptExtractor miniMaxReceiptExtractor(
            MiniMaxApiClient client,
            PdfTextExtractor pdfExtractor,
            ReceiptResponseParser parser,
            MinimaxAiProperties properties) {
        return new MiniMaxReceiptExtractor(client, pdfExtractor, parser, properties);
    }

    @Bean
    public MiniMaxDocumentTextExtractor miniMaxDocumentTextExtractor(
            MiniMaxApiClient client,
            MinimaxAiProperties properties,
            PdfTextExtractor pdfExtractor) {
        return new MiniMaxDocumentTextExtractor(client, properties, pdfExtractor);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank() || "dev-placeholder".equals(key)) {
            throw new IllegalArgumentException(
                    "MINIMAX_API_KEY is missing or set to the dev placeholder");
        }
    }
}

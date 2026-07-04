package com.ticketapp.minimaxai.autoconfigure;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;

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
 * <p>{@link ComponentScan} picks up the {@code @Component} classes
 * in {@code com.ticketapp.minimaxai}:
 * <ul>
 *   <li>{@link com.ticketapp.minimaxai.PdfTextExtractor} — no-arg
 *       helper for PDF text extraction.</li>
 *   <li>{@link com.ticketapp.minimaxai.MiniMaxApiClient} — wraps an
 *       {@link OpenAIClient}. Spring injects the bean declared
 *       below.</li>
 *   <li>{@link com.ticketapp.minimaxai.MiniMaxReceiptExtractor} — the
 *       {@code ReceiptExtractor} port implementation that wires
 *       everything together.</li>
 * </ul>
 *
 * <p>{@link EnableConfigurationProperties} binds the
 * operator-layer knobs under {@code ticketapp.ai.minimax.*} so
 * Spring auto-injects them into the {@link #openAIClient(MinimaxAiProperties)}
 * factory below.
 */
@AutoConfiguration
@ComponentScan(basePackages = "com.ticketapp.minimaxai")
@EnableConfigurationProperties(MinimaxAiProperties.class)
@Slf4j
public class MinimaxAiAutoConfiguration {

    /**
     * The OpenAI-compatible HTTP client. Built from the operator's
     * {@code baseUrl}, {@code apiKey}, and {@code timeoutMs}. The
     * OpenAI Java SDK does not ship its own Spring autoconfig, so we
     * declare this here. The {@link com.ticketapp.minimaxai.MiniMaxApiClient}
     * {@code @Component} consumes it via Spring DI.
     *
     * <p>The INFO log on boot is intentional: when the
     * {@code baseUrl} is misconfigured (missing env var → empty
     * string → SDK falls through to its hardcoded OpenAI default)
     * every subsequent extraction returns a 401 from
     * {@code api.openai.com} instead of the actual provider. Logging
     * the resolved URL on boot gives the operator the answer in one
     * place rather than chasing logs.
     */
    @Bean
    public OpenAIClient openAIClient(MinimaxAiProperties properties) {
        log.info("MiniMax OpenAI client configured: baseUrl={} model={} timeoutMs={}",
                properties.baseUrl(), properties.model(), properties.timeoutMs());
        return OpenAIOkHttpClient.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(properties.apiKey())
                .timeout(Duration.ofMillis(properties.timeoutMs()))
                .build();
    }
}

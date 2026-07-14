package com.ticketapp.bff.ai;

import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtraction.ProductLine;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.ai.ReceiptExtraction;
import com.ticketapp.domain.ai.ReceiptExtractionException;
import com.ticketapp.domain.ai.ReceiptExtractionRequest;
import com.ticketapp.domain.ai.ReceiptExtractionResult;
import com.ticketapp.domain.ai.ReceiptExtractor;
import com.ticketapp.persistence.JdbcTicketExtractionRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Month;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketExtractionService}.
 *
 * <p>After ADR 0007 the orchestrator depends only on the
 * {@link ReceiptExtractor} port — provider-specific concerns
 * (PDF/image routing, response parsing, raw-reply capture) live in
 * the provider module. Tests here pin the orchestrator contract:
 * <ul>
 *   <li>On success: ticket → IN_PROGRESS, extraction row inserted.</li>
 *   <li>On {@link ReceiptExtractionException}: ticket → IN_PROGRESS
 *       then marked ON_ERROR with the failure reason attached.</li>
 *   <li>On any other unexpected exception: same ON_ERROR marking.</li>
 *   <li>Long error messages are truncated to
 *       {@link TicketExtractionService#ERROR_MESSAGE_MAX_CHARS} so a
 *       runaway raw reply cannot bloat the row.</li>
 *   <li>Already extracted: skip without touching the ticket.</li>
 *   <li>The request handed to the port carries the ticket's bytes
 *       and content type unchanged.</li>
 *   <li>The persisted {@link TicketExtraction} merges the port's
 *       result with the ticket id, current time, and the raw
 *       reply returned by the port.</li>
 * </ul>
 *
 * <p>The orchestrator operates as the ticket's owner — there is no
 * separate user session for the cron tick — so the lookup that
 * refreshes the entity before marking ON_ERROR uses the ticket's own
 * ownerId rather than a system scope. The {@code markError} path is
 * tested by stubbing the owner-scoped {@code findById}.
 *
 * <p>Provider-specific tests (PDF routing, response parsing,
 * {@code <think>} stripping) live in
 * {@code minimax-ai/src/test/.../MiniMaxReceiptExtractorTest}.
 */
class TicketExtractionServiceTest {

    private static final String MODEL = "MiniMax-M3";
    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TicketRepository tickets;
    private TicketExtractionRepository extractions;
    private JdbcTicketExtractionRepository jdbcExtractions;
    private ReceiptExtractor receiptExtractor;
    private TicketExtractionService service;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        extractions = mock(TicketExtractionRepository.class);
        jdbcExtractions = mock(JdbcTicketExtractionRepository.class);
        receiptExtractor = mock(ReceiptExtractor.class);
        service = new TicketExtractionService(
                tickets, extractions, jdbcExtractions, receiptExtractor);
    }

    private static Ticket sampleTicket(UUID id) {
        return new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", new byte[]{1, 2, 3}, null, 0);
    }

    private static Ticket sampleTicket(UUID id, byte[] bytes) {
        return new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", bytes, null, 0);
    }

    @Test
    void successPathPersistsExtractionAndLeavesTicketInProgress() throws Exception {
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona",
                                LocalDate.of(2026, Month.JULY, 4),
                                "food",
                                List.of(new ProductLine(
                                        "Bread",
                                        new BigDecimal("1"),
                                        "unit",
                                        new BigDecimal("1.20"),
                                        new BigDecimal("1.20"))),
                                new BigDecimal("1.20"),
                                "EUR"),
                        "{\"merchant\":\"Mercadona\"}",
                        MODEL));

        boolean processed = service.processTicket(open);

        assertThat(processed).isTrue();
        verify(tickets).save(argThat(t -> t.status() == Status.IN_PROGRESS));
        verify(jdbcExtractions).recordAttempt(id);
        ArgumentCaptor<TicketExtraction> cap = ArgumentCaptor.forClass(TicketExtraction.class);
        verify(extractions).save(cap.capture());
        TicketExtraction saved = cap.getValue();
        assertThat(saved.ticketId()).isEqualTo(id);
        assertThat(saved.merchant()).isEqualTo("Mercadona");
        assertThat(saved.currency()).isEqualTo("EUR");
        assertThat(saved.model()).isEqualTo(MODEL);
        assertThat(saved.rawResponse()).isEqualTo("{\"merchant\":\"Mercadona\"}");
        // No revert to OPEN on the success path.
        verify(tickets, never()).save(argThat(t -> t.status() == Status.OPEN && t.id().equals(id)));
    }

    @Test
    void extractorFailureMarksTicketOnErrorWithMessage() throws Exception {
        // The orchestrator refreshes the ticket via owner-scoped
        // findById(id, ownerId) before writing ON_ERROR so a
        // concurrent delete can't resurrect a stale copy.
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, "MiniMax returned 500"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        // Two saves: first IN_PROGRESS at the start of the call, then
        // ON_ERROR after the failure. Neither transitions back to
        // OPEN — the failure is terminal from the scheduler's POV.
        verify(tickets, times(2)).save(any(Ticket.class));
        verify(tickets).save(argThat(t -> t.status() == Status.IN_PROGRESS));
        verify(tickets).save(argThat(t ->
                t.status() == Status.ON_ERROR
                        && t.errorMessage() != null
                        && t.errorMessage().contains("500")
                        && t.errorMessage().contains("MiniMax returned 500")));
        verify(extractions, never()).save(any());
    }

    @Test
    void longErrorMessageIsTruncatedBeforePersist() throws Exception {
        // The error_message column is TEXT, but the service bounds
        // the message length so a runaway raw-reply (e.g. a multi-KB
        // <think> dump) cannot bloat the row. The persisted message
        // must end with the truncation marker so operators reading
        // the dashboard know they are not seeing the full text.
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        String huge = "x".repeat(TicketExtractionService.ERROR_MESSAGE_MAX_CHARS + 500);
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(502, huge));

        service.processTicket(open);

        verify(tickets).save(argThat(t -> {
            String msg = t.errorMessage();
            return t.status() == Status.ON_ERROR
                    && msg != null
                    && msg.length() <= TicketExtractionService.ERROR_MESSAGE_MAX_CHARS
                            + "...[truncated]".length()
                    && msg.endsWith("...[truncated]");
        }));
    }

    @Test
    void alreadyExtractedTicketsAreSkipped() throws Exception {
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id, new byte[]{1});
        when(extractions.findByTicketId(id)).thenReturn(Optional.of(
                new TicketExtraction(id, "X", LocalDate.now(), null,
                        List.of(), BigDecimal.ZERO, "EUR", MODEL,
                        Instant.now(), "{}")));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(tickets, never()).save(any());
        verifyNoExtractorCall();
    }

    @Test
    void ticketBytesAndContentTypeAreForwardedToThePort() throws Exception {
        UUID id = UUID.randomUUID();
        byte[] bytes = new byte[]{1, 2, 3, 4};
        Ticket png = new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", bytes, null, 0);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(png));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "X", LocalDate.of(2026, Month.JANUARY, 1), "other",
                                List.of(), BigDecimal.ONE, "EUR"),
                        "{}", MODEL));

        service.processTicket(png);

        ArgumentCaptor<ReceiptExtractionRequest> cap =
                ArgumentCaptor.forClass(ReceiptExtractionRequest.class);
        verify(receiptExtractor).extract(cap.capture());
        ReceiptExtractionRequest sent = cap.getValue();
        assertThat(sent.content()).isEqualTo(bytes);
        assertThat(sent.contentType()).isEqualTo("image/png");
    }

    @Test
    void saveFailureMarksTicketOnError() throws Exception {
        // The previous contract reverted the ticket to OPEN when the
        // extraction-row insert failed. With the new contract the
        // ticket lands in ON_ERROR so the scheduler does not pick it
        // up on the next tick and loop on the same broken write.
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id, new byte[]{1});
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "X", LocalDate.of(2026, Month.JANUARY, 1), "other",
                                List.of(), BigDecimal.ONE, "EUR"),
                        "{}", MODEL));
        when(extractions.save(any())).thenThrow(new RuntimeException("DB boom"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        verify(tickets).save(argThat(t -> t.status() == Status.ON_ERROR
                && t.errorMessage() != null
                && t.errorMessage().contains("DB boom")));
    }

    @Test
    void markErrorNoopWhenTicketVanishes() throws Exception {
        // The refresh lookup returns empty (concurrent delete) — the
        // orchestrator must not throw, the cron tick just skips the
        // missing ticket and resumes on the next one.
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.empty()); // race: gone
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, "boom"));

        boolean processed = service.processTicket(open);

        assertThat(processed).isFalse();
        // Only the initial IN_PROGRESS save — the ON_ERROR save was
        // skipped because the refresh returned empty.
        verify(tickets, times(1)).save(any(Ticket.class));
    }

    @Test
    void attemptsCounterIsIncrementedOnEveryProcessTicketCall() throws Exception {
        // The dashboard surfaces a per-ticket "attempts" counter so the
        // user can see how many AI tries a stuck extraction has burned.
        // The orchestrator must bump it on every call — both success
        // and failure — so the number reflects reality regardless of
        // outcome. Counter starts at 0 on the fixture, so the
        // persisted save should carry attempts == 1 on the first
        // (IN_PROGRESS) save.
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(receiptExtractor.extract(any())).thenReturn(
                new ReceiptExtraction(
                        new ReceiptExtractionResult(
                                "Mercadona",
                                LocalDate.of(2026, Month.JULY, 4),
                                "food",
                                List.of(),
                                new BigDecimal("1.20"),
                                "EUR"),
                        "{}",
                        MODEL));

        service.processTicket(open);

        // First save is the IN_PROGRESS flip with attempts bumped to 1.
        verify(tickets).save(argThat(t ->
                t.status() == Status.IN_PROGRESS && t.attempts() == 1));
    }

    @Test
    void attemptsCounterPersistsAcrossRetriesForSameTicket() throws Exception {
        // After a processTicket call the persisted counter must be
        // reflected on the ON_ERROR save too. The orchestrator calls
        // markError which re-reads via owner-scoped findById before
        // writing ON_ERROR — in production that re-read lands on the
        // DB row persisted by the IN_PROGRESS save, so the bumped
        // counter survives the failure path. The mock has to simulate
        // that round-trip: the saved ticket becomes the next findById
        // answer, otherwise the mock returns the original (attempts=0)
        // and the test would assert a value that only exists in
        // production.
        UUID id = UUID.randomUUID();
        Ticket open = sampleTicket(id);
        when(extractions.findByTicketId(id)).thenReturn(Optional.empty());
        // Mutable holder so the save() answer can publish the bumped
        // ticket that the subsequent markError findById() must return.
        java.util.concurrent.atomic.AtomicReference<Ticket> stored = new java.util.concurrent.atomic.AtomicReference<>(open);
        when(tickets.save(any())).thenAnswer(inv -> {
            Ticket saved = inv.getArgument(0);
            stored.set(saved);
            return saved;
        });
        when(tickets.findById(id, OWNER)).thenAnswer(inv -> Optional.of(stored.get()));
        when(receiptExtractor.extract(any()))
                .thenThrow(new ReceiptExtractionException(500, "boom"));

        service.processTicket(open);

        // Two saves: IN_PROGRESS (attempts==1) then ON_ERROR
        // (attempts==1, errorMessage set). The counter does NOT
        // increment again on the failure path — incrementAttempts()
        // is called once per processTicket invocation.
        verify(tickets, times(2)).save(any(Ticket.class));
        verify(tickets).save(argThat(t ->
                t.status() == Status.IN_PROGRESS && t.attempts() == 1));
        verify(tickets).save(argThat(t ->
                t.status() == Status.ON_ERROR && t.attempts() == 1));
    }

    private void verifyNoExtractorCall() throws Exception {
        verify(receiptExtractor, never()).extract(any());
    }
}
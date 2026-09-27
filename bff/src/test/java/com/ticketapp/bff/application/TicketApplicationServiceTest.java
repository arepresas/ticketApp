package com.ticketapp.bff.application;

import com.ticketapp.bff.ai.DocumentTextExtractionSyncService;
import com.ticketapp.bff.ai.TicketExtractionService;
import com.ticketapp.bff.extraction.TicketExtractionNormaliser;
import com.ticketapp.domain.Ticket;
import com.ticketapp.domain.Ticket.Status;
import com.ticketapp.domain.TicketExtraction;
import com.ticketapp.domain.TicketExtractionRepository;
import com.ticketapp.domain.TicketRepository;
import com.ticketapp.domain.identity.AuthenticatedUser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link TicketApplicationService} — the two
 * multi-step write flows extracted from the former God controller.
 */
class TicketApplicationServiceTest {

    private static final UUID OWNER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private TicketRepository tickets;
    private TicketExtractionRepository extractions;
    private TicketExtractionService extractionService;
    private DocumentTextExtractionSyncService ocrService;
    private TicketExtractionNormaliser normaliser;
    private TicketApplicationService service;
    private AuthenticatedUser user;

    @BeforeEach
    void setUp() {
        tickets = mock(TicketRepository.class);
        extractions = mock(TicketExtractionRepository.class);
        extractionService = mock(TicketExtractionService.class);
        ocrService = mock(DocumentTextExtractionSyncService.class);
        normaliser = mock(TicketExtractionNormaliser.class);
        service = new TicketApplicationService(
                tickets, extractions, extractionService, ocrService, normaliser);
        user = new AuthenticatedUser(OWNER, "sub", "u@x.com", "U", null,
                Instant.now(), Instant.now());
    }

    private static Ticket openTicket(UUID id) {
        return new Ticket(id, OWNER, "r.png", "", Status.OPEN,
                Instant.now(), Instant.now(),
                "image/png", "r.png", new byte[]{1, 2, 3}, null, 0, null, null, 0);
    }

    @Test
    void changeStatusToDoneWithoutExtractionTriggersPipeline() {
        UUID id = UUID.randomUUID();
        Ticket open = openTicket(id);
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        TicketExtraction doneExtraction = new TicketExtraction(
                id, "Mercadona", LocalDate.of(2026, 7, 4), "food", List.of(),
                new BigDecimal("1.00"), "EUR", "stub", Instant.now(), "{}", null);
        when(extractions.findByTicketId(id, OWNER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doneExtraction));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Ticket result = service.changeStatus(id, user, Status.DONE);

        verify(extractionService).processTicket(any(Ticket.class));
        verify(normaliser).normaliseOnDone(any(Ticket.class));
        assertThat(result.status()).isEqualTo(Status.DONE);
    }

    @Test
    void normaliserFailureDoesNotRollBackStatusFlip() {
        UUID id = UUID.randomUUID();
        Ticket open = openTicket(id);
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        TicketExtraction doneExtraction = new TicketExtraction(
                id, "Mercadona", LocalDate.of(2026, 7, 4), "food", List.of(),
                new BigDecimal("1.00"), "EUR", "stub", Instant.now(), "{}", null);
        when(extractions.findByTicketId(id, OWNER))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(doneExtraction));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));
        org.mockito.Mockito.doThrow(new RuntimeException("catalogue boom"))
                .when(normaliser).normaliseOnDone(any(Ticket.class));

        Ticket result = service.changeStatus(id, user, Status.DONE);

        assertThat(result.status()).isEqualTo(Status.DONE);
    }

    @Test
    void reMarkingAnAlreadyDoneTicketRetriesTheCatalogueApply() {
        // The recovery path for a DONE ticket whose catalogue apply
        // failed. The branch is keyed on the requested target status,
        // not on an actual transition, so a second DONE re-runs the
        // normaliser (idempotently) instead of being a no-op.
        UUID id = UUID.randomUUID();
        Ticket done = openTicket(id).withStatus(Status.DONE);
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(done));
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.of(
                new TicketExtraction(
                        id, "Mercadona", LocalDate.of(2026, 7, 4), "food", List.of(),
                        new BigDecimal("1.00"), "EUR", "stub", Instant.now(), "{}", null)));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.changeStatus(id, user, Status.DONE);

        verify(normaliser).normaliseOnDone(any(Ticket.class));
    }

    @Test
    void changeStatusOnMissingTicketIs404() {
        UUID id = UUID.randomUUID();
        when(tickets.findById(id, OWNER)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.changeStatus(id, user, Status.DONE))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(
                        ((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
        verify(extractionService, never()).processTicket(any());
    }

    @Test
    void failedPipelineKeepsOnErrorInsteadOfForcingDone() {
        // The fallback triggered the pipeline but it failed: the
        // re-read sees the ON_ERROR row processTicket persisted and
        // the service returns it instead of flipping to DONE with
        // an empty catalogue.
        UUID id = UUID.randomUUID();
        Ticket open = openTicket(id);
        Ticket failed = open.markError("MiniMax returned 500");
        when(tickets.findById(id, OWNER))
                .thenReturn(Optional.of(open))
                .thenReturn(Optional.of(failed));
        when(extractions.findByTicketId(id, OWNER)).thenReturn(Optional.empty());

        Ticket result = service.changeStatus(id, user, Status.DONE);

        assertThat(result.status()).isEqualTo(Status.ON_ERROR);
        assertThat(result.errorMessage()).contains("MiniMax returned 500");
        verify(extractionService).processTicket(any(Ticket.class));
        verify(normaliser, never()).normaliseOnDone(any());
        verify(tickets, never()).save(argThat(t -> t.status() == Status.DONE));
    }

    @Test
    void changeStatusToCancelledSkipsPipelineAndNormaliser() {
        UUID id = UUID.randomUUID();
        Ticket open = openTicket(id);
        when(tickets.findById(id, OWNER)).thenReturn(Optional.of(open));
        when(tickets.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Ticket result = service.changeStatus(id, user, Status.CANCELLED);

        assertThat(result.status()).isEqualTo(Status.CANCELLED);
        verify(extractionService, never()).processTicket(any());
        verify(normaliser, never()).normaliseOnDone(any());
    }
}

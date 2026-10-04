package com.ticketapp.bff.api;

import com.ticketapp.bff.api.dto.DashboardResponse;
import com.ticketapp.bff.dashboard.DashboardService;
import com.ticketapp.bff.security.CurrentUser;
import com.ticketapp.domain.identity.AuthenticatedUser;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The dashboard's read-only aggregate endpoint.
 *
 * <p>Serves the four KPI cards and both charts in a single round trip.
 * The all-tickets table below them is <em>not</em> served here — it
 * reads {@code GET /api/tickets} directly, because a list of tickets
 * is an entity read while this is an aggregate, and the SPA already
 * refreshes the table independently.
 *
 * <p>Owner scoping comes from the repository, not from this class: the
 * principal's id is the only input, and every aggregate behind the port
 * requires it. There is deliberately no unscoped variant, so no future
 * caller can ask for "all users".
 *
 * <p>Auth: requires a valid session. Covered by the {@code /api/**}
 * security chain, so this method only ever runs with a principal.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
public class DashboardController {

    private final DashboardService dashboard;

    @GetMapping
    public ResponseEntity<DashboardResponse> get() {
        AuthenticatedUser user = CurrentUser.get();
        return ResponseEntity.ok(DashboardResponse.of(dashboard.load(user.id())));
    }
}
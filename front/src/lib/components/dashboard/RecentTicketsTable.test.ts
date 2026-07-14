import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { cleanup, render, waitFor } from '@testing-library/svelte';

import type { CreatedTicket } from '../../api/tickets';

// Stub listAllTickets with a hoisted spy so tests can override per-case.
// vi.hoisted runs before the vi.mock factory sees the closure.
const { listAllTicketsStub } = vi.hoisted(() => ({
	listAllTicketsStub: vi.fn<() => Promise<CreatedTicket[]>>()
}));

vi.mock('../../api/tickets', async () => {
	const actual = await vi.importActual<typeof import('../../api/tickets')>(
		'../../api/tickets'
	);
	return {
		...actual,
		listAllTickets: (..._args: unknown[]) => listAllTicketsStub()
	};
});

import RecentTicketsTable from './RecentTicketsTable.svelte';

const sample: CreatedTicket[] = [
	{
		id: 't-001',
		title: 'Mercadona weekly',
		description: 'Saturday shop',
		status: 'DONE',
		createdAt: '2026-03-12T10:00:00Z',
		updatedAt: '2026-03-12T10:00:00Z',
		contentType: 'image/png',
		fileName: 'mercadona.png',
		sizeBytes: 12345
	},
	{
		id: 't-002',
		title: 'Lidl express',
		description: '',
		status: 'OPEN',
		createdAt: '2026-03-15T08:30:00Z',
		updatedAt: '2026-03-15T08:30:00Z',
		contentType: 'application/pdf',
		fileName: 'lidl.pdf',
		sizeBytes: 2048
	},
	{
		id: 't-003',
		title: 'Cancelled receipt',
		description: 'Mistake',
		status: 'CANCELLED',
		createdAt: '2026-03-10T18:00:00Z',
		updatedAt: '2026-03-10T18:00:00Z',
		contentType: null,
		fileName: null,
		sizeBytes: null
	},
	{
		id: 't-004',
		title: 'Failed extraction',
		description: 'AI provider 502',
		status: 'ON_ERROR',
		createdAt: '2026-03-14T12:00:00Z',
		updatedAt: '2026-03-14T12:00:00Z',
		contentType: 'image/jpeg',
		fileName: 'broken.jpg',
		sizeBytes: 4096
	}
];

describe('RecentTicketsTable', () => {
	beforeEach(() => {
		// Seed a fake token so the table's sessionStorage lookup
		// resolves. The mock ignores the value — returns whatever
		// listAllTicketsStub is configured for.
		try {
			window.sessionStorage.setItem('ticketapp.session', 'stub.jwt.token');
		} catch {
			/* sessionStorage may be disabled — the empty-state path is
			   covered by a separate test below */
		}
		listAllTicketsStub.mockReset();
		listAllTicketsStub.mockResolvedValue(sample);
	});

	afterEach(() => {
		cleanup();
		vi.restoreAllMocks();
		try {
			window.sessionStorage.clear();
		} catch {
			/* ignore */
		}
	});

	it('renders the expected column headers', async () => {
		const { container } = render(RecentTicketsTable);
		await waitFor(() => {
			const headers = Array.from(container.querySelectorAll('thead th')).map(
				(th) => th.textContent?.trim() ?? ''
			);
			expect(headers).toEqual(['Ticket', 'File', 'Size', 'Created', 'Status']);
		});
	});

	it('renders one row per ticket with status badge + file label + size', async () => {
		const { container } = render(RecentTicketsTable);

		await waitFor(() => {
			const rows = container.querySelectorAll('[data-testid="ticket-row"]');
			expect(rows).toHaveLength(4);
		});

		const rows = container.querySelectorAll('[data-testid="ticket-row"]');
		expect(rows[0]?.textContent).toContain('Mercadona weekly');
		expect(rows[0]?.textContent).toContain('mercadona.png');
		// sizeBytes = 12345 → human-readable 12.1 KB
		expect(rows[0]?.textContent).toMatch(/12[.,]1\s*KB/);
		// DONE status badge wording
		expect(rows[0]?.querySelector('[data-testid="status-badge"]')?.textContent).toContain(
			'Done'
		);

		expect(rows[1]?.textContent).toContain('Lidl express');
		expect(rows[1]?.textContent).toContain('lidl.pdf');
		expect(rows[1]?.querySelector('[data-testid="status-badge"]')?.textContent).toContain(
			'Open'
		);

		// Cancellated: no file attached → 'no file' fallback + '—' for size.
		expect(rows[2]?.textContent).toContain('Cancelled receipt');
		expect(rows[2]?.textContent).toContain('no file');
		expect(rows[2]?.querySelector('[data-testid="status-badge"]')?.textContent).toContain(
			'Cancelled'
		);

		// ON_ERROR gets its own "Error" label and red badge — without
		// the STATUS_LABEL/statusBadgeClass entries, the badge text
		// would render as empty / the cell would fall through to a
		// missing CSS class. This test pins both.
		expect(rows[3]?.textContent).toContain('Failed extraction');
		expect(rows[3]?.querySelector('[data-testid="status-badge"]')?.textContent).toContain(
			'Error'
		);
		expect(rows[3]?.querySelector('[data-testid="status-badge"]')?.className).toMatch(
			/text-red-/
		);
	});

	it('renders the empty state when there are no tickets', async () => {
		listAllTicketsStub.mockResolvedValue([]);
		const { container } = render(RecentTicketsTable);

		await waitFor(() => {
			expect(container.querySelector('[data-testid="tickets-empty"]')?.textContent).toMatch(
				/No tickets yet/
			);
		});
	});

	it('renders an error alert when listAllTickets rejects', async () => {
		listAllTicketsStub.mockRejectedValueOnce(new Error('boom'));
		const { container } = render(RecentTicketsTable);

		await waitFor(() => {
			expect(container.querySelector('[data-testid="tickets-error"]')?.textContent).toContain(
				'boom'
			);
		});
	});

	// Regression guard: when the user marks a ticket DONE / CANCELLED
	// in the detail view, TicketDetailApp dispatches a `ticket:updated`
	// event. The dashboard table listens for it and re-fetches so the
	// row shows the new status without a manual refresh. If this test
	// ever fails, the dashboard is silently showing stale data after
	// every status change.
	it('re-fetches when ticket:updated fires', async () => {
		const { rerender } = render(RecentTicketsTable);
		// The initial mount calls listAllTickets once.
		await waitFor(() => {
			expect(listAllTicketsStub).toHaveBeenCalledTimes(1);
		});
		const callsAfterMount = listAllTicketsStub.mock.calls.length;

		// Simulate the detail view dispatching the event after a
		// successful status change. The dashboard should react.
		window.dispatchEvent(new CustomEvent('ticket:updated'));

		// Wait for the re-fetch to land. The listener is sync, but
		// the fetch is async; give the microtask queue a tick.
		await waitFor(() => {
			expect(listAllTicketsStub.mock.calls.length).toBeGreaterThan(
				callsAfterMount
			);
		});

		// Sanity: the new call must use the same token flow (i.e.
		// it really went through `load()`, not a stale cached value).
		expect(listAllTicketsStub).toHaveBeenCalledTimes(callsAfterMount + 1);

		rerender({});
	});

	// Belt-and-braces: the document visibilitychange listener also
	// triggers a re-fetch when the dashboard transitions back to
	// visible. Pinned so the fix for the `ticket:updated` race
	// can't silently regress.
	it('re-fetches when document becomes visible', async () => {
		render(RecentTicketsTable);
		await waitFor(() => {
			expect(listAllTicketsStub).toHaveBeenCalledTimes(1);
		});
		const callsAfterMount = listAllTicketsStub.mock.calls.length;

		Object.defineProperty(document, 'visibilityState', {
			configurable: true,
			get: () => 'visible'
		});
		document.dispatchEvent(new Event('visibilitychange'));

		await waitFor(() => {
			expect(listAllTicketsStub.mock.calls.length).toBeGreaterThan(
				callsAfterMount
			);
		});

		// Reset for the next test so other cases don't see the
		// mocked visibilityState.
		Object.defineProperty(document, 'visibilityState', {
			configurable: true,
			get: () => 'visible'
		});
	});

	// The header title doubles as a refresh trigger so the user
	// can refresh by clicking anywhere in the header strip — same
	// pattern as the dedicated Refresh button on the right. Pinned
	// so the role="button" wiring on the title doesn't regress
	// silently (e.g. if someone swaps the click handler for a
	// no-op during a refactor).
	it('re-fetches when the header title is clicked', async () => {
		const { container } = render(RecentTicketsTable);
		await waitFor(() => {
			expect(listAllTicketsStub).toHaveBeenCalledTimes(1);
		});
		const callsAfterMount = listAllTicketsStub.mock.calls.length;

		const title = container.querySelector('[data-testid="tickets-title"]');
		expect(title).not.toBeNull();
		title?.dispatchEvent(new MouseEvent('click', { bubbles: true }));

		await waitFor(() => {
			expect(listAllTicketsStub.mock.calls.length).toBeGreaterThan(
				callsAfterMount
			);
		});
		expect(listAllTicketsStub).toHaveBeenCalledTimes(callsAfterMount + 1);
	});

	// Re-clicking the global Header's Dashboard link dispatches a
	// `dashboard:refresh` window event (the router detects same-route
	// navigation). The table listens for it so the user gets a fresh
	// fetch on link re-click — the visible affordance matches "click
	// reloads". Pinned so removing the listener silently can't regress.
	it('re-fetches when a dashboard:refresh event fires', async () => {
		render(RecentTicketsTable);
		await waitFor(() => {
			expect(listAllTicketsStub).toHaveBeenCalledTimes(1);
		});
		const callsAfterMount = listAllTicketsStub.mock.calls.length;

		window.dispatchEvent(new CustomEvent('dashboard:refresh'));

		await waitFor(() => {
			expect(listAllTicketsStub.mock.calls.length).toBeGreaterThan(
				callsAfterMount
			);
		});
		expect(listAllTicketsStub).toHaveBeenCalledTimes(callsAfterMount + 1);
	});
});

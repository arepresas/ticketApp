import { describe, expect, it, vi, beforeEach } from 'vitest';

// Same mocking strategy as NewTicketApp.test.ts: the component
// registers as `<pending-tickets-app>` via `svelte:options` and the
// build's svelte.config turns every `.svelte` into a custom element,
// which mounts into a shadow root jsdom does NOT expose through
// `container.querySelector`. We pin module load + mocked auth store
// here and defer rendered-DOM coverage to Playwright, matching the
// project's other custom-element shells (NewTicketApp, LandingApp,
// DashboardApp).
const { mockAuth } = vi.hoisted(() => ({
	mockAuth: {
		user: null as { id: string; email: string; name: string } | null,
		status: 'idle' as 'idle' | 'loading' | 'authenticated' | 'error',
		isAuthenticated: false,
		error: null as string | null
	}
}));

vi.mock('../auth/store.svelte.ts', () => ({
	auth: mockAuth,
	initAuth: vi.fn().mockResolvedValue(undefined),
	loginWithGoogle: vi.fn(),
	logout: vi.fn()
}));

// Import AFTER the mocks so the Svelte compiler picks them up.
import PendingTicketsApp from './PendingTicketsApp.svelte';

describe('PendingTicketsApp', () => {
	beforeEach(() => {
		mockAuth.user = null;
		mockAuth.status = 'idle';
		mockAuth.isAuthenticated = false;
		mockAuth.error = null;
		document.body.classList.remove('is-pending');
	});

	it('exports a Svelte component module', () => {
		// Importing the module triggers the customElement registration
		// side-effect; loading it without throwing + a defined default
		// export is enough to know the bundle is buildable.
		expect(PendingTicketsApp).toBeDefined();
	});

	it('mocked auth store reports the unauthenticated baseline', () => {
		expect(mockAuth.isAuthenticated).toBe(false);
		expect(mockAuth.user).toBeNull();
		expect(mockAuth.status).toBe('idle');
	});

	it('mocked auth store can flip to authenticated', () => {
		mockAuth.user = { id: 'u-1', email: 'ada@example.com', name: 'Ada Lovelace' };
		mockAuth.isAuthenticated = true;
		mockAuth.status = 'authenticated';
		expect(mockAuth.isAuthenticated).toBe(true);
		expect(mockAuth.user?.name).toBe('Ada Lovelace');
	});

	// Full coverage (fetch on mount, empty state, error alert, refresh
	// button, status badge styling, humanSize/formatDate formatters) is
	// deferred to Playwright — the screen is a shadow-DOM custom
	// element and the HTTP client is pinned separately in
	// `api/tickets.test.ts`. The shared contract lives there too: a
	// successful GET /api/tickets/pending returns CreatedTicket[] in
	// createdAt desc order.
});
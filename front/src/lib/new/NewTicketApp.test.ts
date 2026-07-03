import { describe, expect, it, vi, beforeEach } from 'vitest';

// `vi.mock` factory is hoisted to the top of the file — it runs before
// any `const` declarations, so the mocked store reference must live
// inside `vi.hoisted(...)` to be reachable from both the factory and
// the test bodies.
//
// The component declares `<svelte:options customElement="new-ticket-app" />`
// and the project's svelte.config.js also turns every `.svelte` into a
// custom element. That makes `render()` mount the body into a shadow
// root that jsdom does NOT expose through `container.querySelector`, so
// behavioural tests against the rendered DOM aren't viable here. The
// repo's other custom-element shells (DashboardApp, LandingApp) follow
// the same pattern: pin module load + mocked store, defer the rendered-
// DOM coverage to Playwright.
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
import NewTicketApp from './NewTicketApp.svelte';

describe('NewTicketApp', () => {
	beforeEach(() => {
		mockAuth.user = null;
		mockAuth.status = 'idle';
		mockAuth.isAuthenticated = false;
		mockAuth.error = null;
		document.body.classList.remove('is-new');
	});

	it('exports a Svelte component module', () => {
		// Importing the module triggers the customElement registration
		// side-effect; loading it without throwing + a defined default
		// export is enough to know the bundle is buildable.
		expect(NewTicketApp).toBeDefined();
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

	// Full coverage (drag-and-drop, file validation, submit, toast, body-
	// class toggle) is deferred to Playwright — the upload screen is a
	// shadow-DOM custom element, and `<input type="file">` interactions
	// plus `URL.createObjectURL` previews are not reliably driven from
	// jsdom. The pure validation rules are pinned separately in
	// `validation.test.ts`. The HTTP client is pinned in `api/tickets.test.ts`.
});
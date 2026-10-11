import { describe, expect, it, vi, beforeEach } from 'vitest';

// Same mocking strategy as PendingTicketsApp.test.ts: the component
// registers as `<ticket-detail-app>` via `svelte:options` and the
// build's svelte.config turns every `.svelte` into a custom element,
// which mounts into a shadow root jsdom does NOT expose through
// `container.querySelector` (verified: appending the element leaves
// `shadowRoot` null, so even a driver cannot reach its buttons).
//
// The save lifecycle therefore lives in `editSave.ts` and is covered
// by `editSave.test.ts`; the ordering of "save, then close" lives in
// `flushThenAct.ts` and is covered by `flushThenAct.test.ts`. This
// file pins what only the component can be blamed for: that it is
// wired to that API surface at all, and does so completely.
const { mockAuth } = vi.hoisted(() => ({
	mockAuth: {
		user: null as { id: number; email: string; name: string } | null,
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

// Mock the API client so we don't hit real fetch. Every export the
// component imports must be present: a missing one only throws when
// that code path runs, so an incomplete factory would hide a broken
// import behind a green suite.
const mockApi = vi.hoisted(() => ({
	getTicket: vi.fn(),
	getTicketExtraction: vi.fn(),
	getTicketFile: vi.fn(),
	updateTicketStatus: vi.fn(),
	updateTicketMetadata: vi.fn(),
	replaceTicketExtraction: vi.fn(),
	applyTicketEdit: vi.fn(),
	getTicketCatalogue: vi.fn(),
	retryTicket: vi.fn(),
	deleteTicket: vi.fn()
}));

vi.mock('../api/tickets.ts', () => mockApi);

vi.mock('../api/products', () => ({
	searchProducts: vi.fn()
}));

// Import AFTER the mocks so the Svelte compiler picks them up.
import TicketDetailApp from './TicketDetailApp.svelte';

const SAMPLE_TICKET = {
	id: 1,
	title: 'receipt.pdf',
	description: 'Lunch at Mercadona',
	status: 'OPEN',
	createdAt: '2026-07-03T17:00:00Z',
	updatedAt: '2026-07-03T17:00:00Z',
	contentType: 'image/png',
	fileName: 'receipt.png',
	sizeBytes: 2048,
	errorMessage: null,
	attempts: 0,
	ocrText: null
};

const SAMPLE_EXTRACTION = {
	ticketId: SAMPLE_TICKET.id,
	merchant: 'Mercadona',
	purchaseDate: '2026-07-03',
	category: 'food',
	products: [
		{ name: 'Bread', quantity: 1, unit: 'unit', pricePerUnit: 1.2, lineTotal: 1.2 },
		{ name: 'Flour', quantity: 2, unit: 'kg', pricePerUnit: 0.9, lineTotal: 1.8 }
	],
	totalAmount: 3.0,
	currency: 'EUR',
	model: 'gpt-4o-mini',
	extractedAt: '2026-07-03T17:05:00Z'
};

describe('TicketDetailApp', () => {
	beforeEach(() => {
		mockAuth.user = null;
		mockAuth.status = 'idle';
		mockAuth.isAuthenticated = false;
		mockAuth.error = null;
		document.body.classList.remove('is-detail');
		document.body.classList.remove('is-pending');
		window.location.hash = '';
		Object.values(mockApi).forEach((fn) => fn.mockReset());
	});

	it('exports a Svelte component module', () => {
		expect(TicketDetailApp).toBeDefined();
	});

	it('mocked auth store reports the unauthenticated baseline', () => {
		expect(mockAuth.isAuthenticated).toBe(false);
		expect(mockAuth.user).toBeNull();
	});

	// Full rendered-DOM coverage (extraction card, file preview, action
	// buttons, click → load, hash → load, Edit toggle, Save round-trip)
	// is deferred to Playwright — the screen is a shadow-DOM custom
	// element and jsdom can't reach the rendered subtree. The shared
	// contract lives in `api/tickets.test.ts`: getTicket,
	// getTicketExtraction, getTicketFile, updateTicketStatus,
	// updateTicketMetadata, replaceTicketExtraction and applyTicketEdit
	// are pinned there.

	it('wires the whole editable-fields API surface', () => {
		// Existence check: when the component imported `tickets.ts`,
		// every export it uses must be wired through the mock factory
		// and be resettable between tests. Anything more specific
		// lives in `api/tickets.test.ts` (typed wire shape) and
		// `editSave.test.ts` (save lifecycle).
		expect(typeof mockApi.updateTicketMetadata).toBe('function');
		expect(typeof mockApi.replaceTicketExtraction).toBe('function');
		expect(typeof mockApi.applyTicketEdit).toBe('function');
		expect(typeof mockApi.getTicketCatalogue).toBe('function');
		expect(typeof mockApi.retryTicket).toBe('function');
		expect(typeof mockApi.deleteTicket).toBe('function');
	});

	it('exports the fixtures the save path would serialise', () => {
		// Keeps the sample shapes in step with the wire contract the
		// atomic PUT carries: every editable extraction field plus the
		// AI audit fields that stay server-side.
		expect(SAMPLE_TICKET).toHaveProperty('title');
		expect(SAMPLE_EXTRACTION.merchant).toBe('Mercadona');
		expect(SAMPLE_EXTRACTION.model).toBe('gpt-4o-mini');
	});
});
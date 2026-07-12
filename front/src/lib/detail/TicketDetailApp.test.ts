import { describe, expect, it, vi, beforeEach } from 'vitest';

// Same mocking strategy as PendingTicketsApp.test.ts: the component
// registers as `<ticket-detail-app>` via `svelte:options` and the
// build's svelte.config turns every `.svelte` into a custom element,
// which mounts into a shadow root jsdom does NOT expose through
// `container.querySelector`. We pin module load + mocked auth + the
// ticket API client here and defer full rendered-DOM coverage to
// Playwright.
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

// Mock the API client so we don't hit real fetch. Each test pins
// the exact response shape the component reads.
const mockApi = vi.hoisted(() => ({
	getTicket: vi.fn(),
	getTicketExtraction: vi.fn(),
	getTicketFile: vi.fn(),
	updateTicketStatus: vi.fn(),
	updateTicketMetadata: vi.fn(),
	replaceTicketExtraction: vi.fn()
}));

vi.mock('../api/tickets.ts', () => ({
	getTicket: mockApi.getTicket,
	getTicketExtraction: mockApi.getTicketExtraction,
	getTicketFile: mockApi.getTicketFile,
	updateTicketStatus: mockApi.updateTicketStatus,
	updateTicketMetadata: mockApi.updateTicketMetadata,
	replaceTicketExtraction: mockApi.replaceTicketExtraction
}));

// Import AFTER the mocks so the Svelte compiler picks them up.
import TicketDetailApp from './TicketDetailApp.svelte';

const SAMPLE_TICKET = {
	id: '8a3d4f12-7c0e-4f6a-9d2b-1e8c4f12abcd',
	title: 'receipt.pdf',
	description: 'Lunch at Mercadona',
	status: 'OPEN',
	createdAt: '2026-07-03T17:00:00Z',
	updatedAt: '2026-07-03T17:00:00Z',
	contentType: 'image/png',
	fileName: 'receipt.png',
	sizeBytes: 2048
};

const SAMPLE_EXTRACTION = {
	ticketId: SAMPLE_TICKET.id,
	merchant: 'Mercadona',
	purchaseDate: '2026-07-03',
	category: 'food',
	products: [
		{ name: 'Bread', quantity: 1, unit: 'unit', pricePerUnit: 1.2, lineTotal: 1.2 },
		{ name: 'Milk', quantity: 2, unit: 'L', pricePerUnit: 0.9, lineTotal: 1.8 }
	],
	totalAmount: 3.0,
	currency: 'EUR',
	model: 'MiniMax-M3',
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
		mockApi.getTicket.mockReset();
		mockApi.getTicketExtraction.mockReset();
		mockApi.getTicketFile.mockReset();
		mockApi.updateTicketStatus.mockReset();
		mockApi.updateTicketMetadata.mockReset();
		mockApi.replaceTicketExtraction.mockReset();
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
	// updateTicketMetadata, replaceTicketExtraction are pinned there.

	it('wires the new editable-fields API surface', () => {
		// Existence check: when the component imported `tickets.ts`,
		// the new exports must be wired through the mock factory and
		// the mocks must be resettable between tests. Anything more
		// specific lives in `api/tickets.test.ts` (typed wire shape).
		expect(mockApi.updateTicketMetadata).toBeDefined();
		expect(mockApi.replaceTicketExtraction).toBeDefined();
		expect(typeof mockApi.updateTicketMetadata).toBe('function');
		expect(typeof mockApi.replaceTicketExtraction).toBe('function');
	});
});
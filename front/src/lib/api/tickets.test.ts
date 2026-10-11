import { afterEach, describe, expect, it, vi } from 'vitest';

import {
	applyTicketEdit,
	createTicket,
	deleteTicket,
	getTicket,
	getTicketExtraction,
	getTicketFile,
	listPendingTickets,
	TicketApiError,
	updateTicketStatus,
	type CreatedTicket,
	type EditableExtraction,
	type TicketExtraction
} from '../api/tickets';

const sampleCreated: CreatedTicket = {
	id: 1,
	title: 'receipt.pdf',
	description: 'Lunch at Mercadona',
	status: 'OPEN',
	createdAt: '2026-07-03T17:00:00Z',
	updatedAt: '2026-07-03T17:00:00Z',
	contentType: 'application/pdf',
	fileName: 'receipt.pdf',
	sizeBytes: 2048,
	errorMessage: null,
	attempts: 0,
	ocrText: null
};

const sampleExtraction: TicketExtraction = {
	ticketId: sampleCreated.id,
	merchant: 'Mercadona',
	purchaseDate: '2026-07-03',
	category: 'food',
	products: [
		{
			name: 'Bread',
			quantity: 1,
			unit: 'unit',
			pricePerUnit: 1.2,
			lineTotal: 1.2
		}
	],
	totalAmount: 1.2,
	currency: 'EUR',
	model: 'gpt-4o-mini',
	extractedAt: '2026-07-03T17:05:00Z'
};

function mockResponse(body: unknown, init: { status?: number; ok?: boolean } = {}): Response {
	const status = init.status ?? (init.ok === false ? 500 : 201);
	const ok = init.ok ?? status < 400;
	return new Response(JSON.stringify(body), {
		status,
		headers: { 'content-type': 'application/json' }
	});
}

describe('createTicket', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('POSTs multipart with file + Bearer token and returns the wire DTO', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse(sampleCreated, { status: 201 }));

		const file = new File(['%PDF-1.4 hello'], 'receipt.pdf', { type: 'application/pdf' });
		const created = await createTicket('test.jwt.token', file, 'Lunch at Mercadona');

		expect(created).toEqual(sampleCreated);
		expect(fetchSpy).toHaveBeenCalledTimes(1);

		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/api/tickets');
		expect(init.method).toBe('POST');
		// Multipart boundary is added by the browser; the client must NOT
		// set Content-Type itself or the boundary is stripped.
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer test.jwt.token');
		expect((init.headers as Record<string, string>)['content-type']).toBeUndefined();
		expect(init.body).toBeInstanceOf(FormData);

		const fd = init.body as FormData;
		expect(fd.get('file')).toBe(file);
		expect(fd.get('description')).toBe('Lunch at Mercadona');
	});

	it('omits the description field when none is supplied', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse(sampleCreated, { status: 201 }));

		const file = new File(['x'], 'a.png', { type: 'image/png' });
		await createTicket('tok', file);

		expect(fetchSpy).toHaveBeenCalledTimes(1);
		const init = fetchSpy.mock.calls[0]?.[1] as RequestInit | undefined;
		expect(init).toBeDefined();
		const fd = init!.body as FormData;
		expect(fd.has('description')).toBe(false);
		expect(fd.get('file')).toBe(file);
	});

	it('throws TicketApiError on non-2xx responses', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'file too large' }, { status: 400, ok: false })
		);

		const file = new File(['x'], 'big.pdf', { type: 'application/pdf' });
		await expect(createTicket('tok', file)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 400,
			message: 'file too large'
		});
	});

	it('falls back to "HTTP {status}" when the error body has no message field', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response('not json', { status: 502, headers: { 'content-type': 'text/plain' } })
		);

		const file = new File(['x'], 'a.png', { type: 'image/png' });
		await expect(createTicket('tok', file)).rejects.toBeInstanceOf(TicketApiError);
		await expect(createTicket('tok', file)).rejects.toThrow('HTTP 502');
	});
});

describe('listPendingTickets', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('GETs /api/tickets/pending and returns the wire array', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse([sampleCreated])
		);
		const list = await listPendingTickets('tok');
		expect(list).toEqual([sampleCreated]);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe('/api/tickets/pending');
		expect(init.method).toBe('GET');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer tok');
	});

	it('throws TicketApiError on non-2xx', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'unauthorized' }, { status: 401, ok: false })
		);
		await expect(listPendingTickets('bad')).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 401
		});
	});
});

describe('getTicket', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('GETs /api/tickets/{id} and returns the ticket', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse(sampleCreated)
		);
		const got = await getTicket('tok', sampleCreated.id);
		expect(got).toEqual(sampleCreated);
		const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}`);
	});

	it('surfaces 404 as TicketApiError (caller decides UI)', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'not found' }, { status: 404, ok: false })
		);
		await expect(getTicket('tok', 9999)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});

describe('getTicketExtraction', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('GETs /api/tickets/{id}/extraction and returns the structured payload', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse(sampleExtraction)
		);
		const ex = await getTicketExtraction('tok', sampleCreated.id);
		expect(ex).toEqual(sampleExtraction);
		const [url] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}/extraction`);
	});

	it('returns null on 404 (no extraction yet, cross-tenant, or missing ticket)', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response('not found', { status: 404 })
		);
		// All three cases (no row, cross-tenant, missing ticket) share
		// the same 404 response — the API treats them as one. The
		// caller renders an empty state for any of them.
		const ex = await getTicketExtraction('tok', sampleCreated.id);
		expect(ex).toBeNull();
	});

	it('throws TicketApiError on non-404 failures', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'server error' }, { status: 500, ok: false })
		);
		await expect(getTicketExtraction('tok', sampleCreated.id)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 500
		});
	});
});

describe('getTicketFile', () => {
	afterEach(() => {
		vi.restoreAllMocks();
		// Some tests call URL.createObjectURL which jsdom doesn't
		// implement by default — patch a stub so the assertion path
		// works. `vi.unstubAllMocks` doesn't restore this so we do it
		// manually.
		(globalThis.URL as { createObjectURL?: unknown }).createObjectURL = undefined;
	});

	it('GETs /api/tickets/{id}/file and returns an object URL + content type', async () => {
		(globalThis.URL as { createObjectURL?: unknown }).createObjectURL = vi.fn(
			() => 'blob:http://localhost/fake-id'
		);
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response(new Uint8Array([1, 2, 3, 4]), {
				status: 200,
				headers: { 'content-type': 'image/png' }
			})
		);

		const file = await getTicketFile('tok', sampleCreated.id);

		expect(file.url).toBe('blob:http://localhost/fake-id');
		expect(file.contentType).toBe('image/png');
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}/file`);
		expect(init.method).toBe('GET');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer tok');
	});

	it('throws TicketApiError on 404', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response('not found', { status: 404 })
		);
		await expect(getTicketFile('tok', sampleCreated.id)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});

describe('updateTicketStatus', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('PATCHes /api/tickets/{id}/status and returns the updated ticket', async () => {
		const updated: CreatedTicket = { ...sampleCreated, status: 'DONE' };
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse(updated)
		);

		const got = await updateTicketStatus('tok', sampleCreated.id, 'DONE');

		expect(got).toEqual(updated);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}/status`);
		expect(init.method).toBe('PATCH');
		const headers = init.headers as Record<string, string>;
		expect(headers.authorization).toBe('Bearer tok');
		expect(headers['content-type']).toBe('application/json');
		expect(JSON.parse(init.body as string)).toEqual({ status: 'DONE' });
	});

	it('throws TicketApiError on 404', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'not found' }, { status: 404, ok: false })
		);
		await expect(updateTicketStatus('tok',  9999, 'DONE')).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});

describe('applyTicketEdit', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	const editable: EditableExtraction = {
		merchant: 'Mercadona Centro',
		purchaseDate: '2026-03-12',
		category: 'food',
		products: [
			{ name: 'Aceite oliva 1L', quantity: 1, unit: 'unit', pricePerUnit: 45.6, lineTotal: 45.6 }
		],
		totalAmount: 45.6,
		currency: 'EUR'
	};

	it('PUTs the atomic edit to /api/tickets/{id} with the Bearer token', async () => {
		// One request, one transaction on the BFF side — this replaced
		// the old PATCH-then-PUT pair that could half-save.
		const updated: CreatedTicket = { ...sampleCreated, title: 'Weekly shop' };
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse(updated));

		const got = await applyTicketEdit('tok', sampleCreated.id, {
			title: 'Weekly shop',
			description: 'edited by hand',
			extraction: editable
		});

		expect(got).toEqual(updated);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}`);
		expect(init.method).toBe('PUT');
		const headers = init.headers as Record<string, string>;
		expect(headers.authorization).toBe('Bearer tok');
		expect(headers['content-type']).toBe('application/json');
		expect(headers.accept).toBe('application/json');
		expect(JSON.parse(init.body as string)).toEqual({
			title: 'Weekly shop',
			description: 'edited by hand',
			extraction: editable
		});
	});

	it('serialises the sparse payload as sent — omitted fields stay omitted', async () => {
		// The BFF treats a missing field as "leave alone", so the
		// client must not send nulls the server would have to guess at.
		const fetchSpy = vi
			.spyOn(globalThis, 'fetch')
			.mockResolvedValue(mockResponse(sampleCreated));

		await applyTicketEdit('tok', sampleCreated.id, { extraction: null });

		const init = fetchSpy.mock.calls[0]?.[1] as RequestInit;
		expect(JSON.parse(init.body as string)).toEqual({ extraction: null });
	});

	it('sends only the extraction half when that is what changed', async () => {
		// Metadata stays out of the payload, so a save cannot clobber a
		// field the user did not touch.
		const fetchSpy = vi
			.spyOn(globalThis, 'fetch')
			.mockResolvedValue(mockResponse(sampleCreated));

		await applyTicketEdit('tok', sampleCreated.id, { extraction: editable });

		const init = fetchSpy.mock.calls[0]?.[1] as RequestInit;
		const body = JSON.parse(init.body as string);
		expect(body).toEqual({ extraction: editable });
		expect(body.title).toBeUndefined();
		expect(body.description).toBeUndefined();
	});

	it('bubbles auth expiry and throws TicketApiError on 401', async () => {
		// Without this pass-through an expired session would leave the
		// user on a stale detail screen with no recovery path.
		const expired = vi.fn();
		window.addEventListener('auth:expired', expired);
		try {
			vi.spyOn(globalThis, 'fetch').mockResolvedValue(
				mockResponse({ message: 'unauthorized' }, { status: 401, ok: false })
			);

			await expect(
				applyTicketEdit('stale', sampleCreated.id, { title: 'x' })
			).rejects.toMatchObject({
				name: 'TicketApiError',
				status: 401,
				message: 'unauthorized'
			});
			expect(expired).toHaveBeenCalledTimes(1);
		} finally {
			window.removeEventListener('auth:expired', expired);
		}
	});

	it('throws TicketApiError on 404 without firing the auth event', async () => {
		// A rejected extraction (the AI has not read the ticket) is a
		// 404, not an expired session.
		const expired = vi.fn();
		window.addEventListener('auth:expired', expired);
		try {
			vi.spyOn(globalThis, 'fetch').mockResolvedValue(
				mockResponse({ error: 'Not Found' }, { status: 404, ok: false })
			);

			await expect(
				applyTicketEdit('tok', 9999, { title: 'x', extraction: editable })
			).rejects.toMatchObject({
				name: 'TicketApiError',
				status: 404,
				message: 'Not Found'
			});
			expect(expired).not.toHaveBeenCalled();
		} finally {
			window.removeEventListener('auth:expired', expired);
		}
	});

	it('returns the persisted ticket on success', async () => {
		const updated: CreatedTicket = {
			...sampleCreated,
			title: 'Weekly shop',
			description: 'edited by hand',
			updatedAt: '2026-07-04T09:00:00Z'
		};
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(mockResponse(updated, { status: 200 }));

		await expect(
			applyTicketEdit('tok', sampleCreated.id, { title: 'Weekly shop' })
		).resolves.toEqual(updated);
	});
});

describe('deleteTicket', () => {
	afterEach(() => {
		vi.restoreAllMocks();
	});

	it('DELETEs /api/tickets/{id} with the Bearer token', async () => {
		const fetchSpy = vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			new Response(null, { status: 204 })
		);

		await deleteTicket('tok', sampleCreated.id);

		expect(fetchSpy).toHaveBeenCalledTimes(1);
		const [url, init] = fetchSpy.mock.calls[0] as [string, RequestInit];
		expect(url).toBe(`/api/tickets/${sampleCreated.id}`);
		expect(init.method).toBe('DELETE');
		expect((init.headers as Record<string, string>).authorization).toBe('Bearer tok');
	});

	it('throws TicketApiError on 404 (missing, foreign or already deleted)', async () => {
		vi.spyOn(globalThis, 'fetch').mockResolvedValue(
			mockResponse({ message: 'not found' }, { status: 404, ok: false })
		);
		await expect(deleteTicket('tok', 9999)).rejects.toMatchObject({
			name: 'TicketApiError',
			status: 404
		});
	});
});
/**
 * HTTP client for the BFF ticket endpoints.
 *
 * Mirrors the `auth/api.ts` conventions: typed errors with `{status}`,
 * shared `parseError` helper for non-2xx responses. The wire contract
 * matches `TicketController.TicketResponse` on the BFF side — bytes are
 * deliberately omitted.
 *
 * 401 handling: every protected call funnels through
 * {@link bubbleAuthExpired} before throwing, which fires the
 * {@code auth:expired} DOM event for `auth/host.ts` to react to. The
 * listener there clears the local session + navigates to dashboard
 * (which hides under the auth gate and reveals the landing page).
 * Without this pass-through, an expired-session 401 would leave the
 * user staring at a stale detail / pending screen with no recovery.
 */
const API_BASE = '/api/tickets';

export class TicketApiError extends Error {
	constructor(
		message: string,
		readonly status: number
	) {
		super(message);
		this.name = 'TicketApiError';
	}
}

export type TicketStatus = 'OPEN' | 'IN_PROGRESS' | 'ON_ERROR' | 'DONE' | 'CANCELLED';

export type CreatedTicket = {
	id: string;
	title: string;
	description: string;
	status: TicketStatus;
	createdAt: string;
	updatedAt: string;
	contentType: string | null;
	fileName: string | null;
	sizeBytes: number | null;
	/**
	 * Last failure reason for tickets that landed on {@link TicketStatus#ON_ERROR}.
	 * Mirrors the BFF's `tickets.error_message` column. `null` for tickets
	 * that have never failed (or whose error was cleared by a status flip).
	 */
	errorMessage: string | null;
	/**
	 * Number of times the AI extraction pipeline has been triggered for
	 * this ticket. Incremented by the BFF orchestrator on every
	 * `processTicket` call (success or failure). Manual PATCH
	 * `/status → OPEN` does NOT bump the counter — it's a tally of AI
	 * attempts, not of user retries. Surfaced in the dashboard so the
	 * user can see how many tries a stuck extraction has burned.
	 */
	attempts: number;
};

const parseError = async (res: Response): Promise<string> => {
	try {
		const body = (await res.json()) as { message?: string; error?: string };
		return body.message ?? body.error ?? `HTTP ${res.status}`;
	} catch {
		return `HTTP ${res.status}`;
	}
};

/**
 * Surface a 401 to the auth host so the local session can be cleared
 * and the user redirected to the landing. No-op when the response
 * isn't 401; safe to call from any path.
 */
const bubbleAuthExpired = (res: Response): void => {
	if (res.status !== 401) return;
	if (typeof window === 'undefined') return; // unit tests in jsdom should still hit this; only guard SSR
	window.dispatchEvent(new CustomEvent('auth:expired'));
};

/**
 * Upload a new ticket to the BFF. Sends a multipart payload with the
 * `file` part (the actual receipt) plus optional `description`. The
 * server picks `title` from the filename when the caller doesn't supply
 * one.
 *
 * Returns the created ticket metadata. The file bytes never come back
 * over the wire — they're persisted server-side and can be fetched
 * separately if/when a download endpoint lands.
 *
 * @param token BFF session JWT. Required: the endpoint refuses 401
 *              without a valid Bearer token.
 * @param file  The receipt (image or PDF). Validated client-side for
 *              type and size; the server re-validates as defense in
 *              depth.
 * @param description Optional free-form note. Defaults to empty.
 */
export const createTicket = async (
	token: string,
	file: File,
	description?: string
): Promise<CreatedTicket> => {
	const fd = new FormData();
	fd.append('file', file);
	if (description && description.trim().length > 0) {
		fd.append('description', description.trim());
	}

	const res = await fetch(API_BASE, {
		method: 'POST',
		headers: { authorization: `Bearer ${token}` },
		// No Content-Type header — the browser must set it with the
		// multipart boundary itself. Setting it manually would strip the
		// boundary and break the upload.
		body: fd
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket;
};

/**
 * Fetch the tickets that haven't reached a terminal state (OPEN +
 * IN_PROGRESS). Backed by {@code GET /api/tickets/pending} on the BFF
 * — the filter happens server-side so the response stays small
 * regardless of the total ticket count.
 *
 * Newest first (ordered by `createdAt` desc). The wire shape matches
 * {@link CreatedTicket} — bytes are omitted, same as the rest of the
 * ticket API.
 *
 * @param token BFF session JWT. The endpoint returns 401 without one.
 */
export const listPendingTickets = async (token: string): Promise<CreatedTicket[]> => {
	const res = await fetch(`${API_BASE}/pending`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket[];
};

/**
 * Fetch every ticket owned by the authenticated user — any status,
 * newest first. Backed by {@code GET /api/tickets} on the BFF; the
 * filter happens server-side so the response stays small regardless
 * of total ticket count.
 *
 * Used by the dashboard's all-tickets table (replaces the
 * recent-only view that was driven by mock data).
 *
 * @param token BFF session JWT. The endpoint returns 401 without one.
 */
export const listAllTickets = async (token: string): Promise<CreatedTicket[]> => {
	const res = await fetch(`${API_BASE}`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket[];
};

/**
 * One row of the structured extraction (the AI-parsed product line).
 * Mirrors the BFF's `ProductLineDto` exactly so the wire round-trips
 * without surprise conversions.
 */
export type ExtractedProductLine = {
	name: string;
	quantity: number;
	unit: string | null;
	pricePerUnit: number;
	lineTotal: number;
};

/**
 * Full structured extraction for one ticket. Mirrors the BFF's
 * `ExtractionResponse`. The {@code rawResponse} field is intentionally
 * NOT exposed — the structured fields above carry the actionable
 * data, and the raw text is verbose (multi-KB on long Lidl receipts).
 */
export type TicketExtraction = {
	ticketId: string;
	merchant: string;
	purchaseDate: string;
	category: string | null;
	products: ExtractedProductLine[];
	totalAmount: number;
	currency: string;
	model: string;
	extractedAt: string;
};

/**
 * Fetch one ticket by id. Returns the wire shape used by the BFF
 * (same as {@link CreatedTicket}). The BFF returns 404 for missing
 * or cross-tenant tickets; the caller decides whether to render a
 * "ticket not found" UI or simply navigate back to the list.
 */
export const getTicket = async (token: string, id: string): Promise<CreatedTicket> => {
	const res = await fetch(`${API_BASE}/${id}`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket;
};

/**
 * Fetch the AI-extracted structured payload for one ticket. Returns
 * {@code null} when the BFF answers 404 — the ticket either doesn't
 * exist, belongs to someone else, or hasn't been extracted yet (still
 * pending or marked ON_ERROR). All three cases surface as "no data
 * yet" in the UI; the detail screen reuses the same empty state.
 *
 * 401 is thrown as a {@link TicketApiError} so the caller can route
 * the user back to the login screen instead of showing empty state.
 */
export const getTicketExtraction = async (
	token: string,
	id: string
): Promise<TicketExtraction | null> => {
	const res = await fetch(`${API_BASE}/${id}/extraction`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
	});
	if (res.status === 404) {
		return null;
	}
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as TicketExtraction;
};

/**
 * Fetch the raw uploaded bytes for one ticket. Returns an object URL
 * suitable for `<img src>` / `<iframe src>` / `<a href>` plus the
 * content type the BFF reports. Caller is responsible for calling
 * {@link URL.revokeObjectURL} when the URL is no longer needed —
 * typically on component destroy or when re-fetching.
 *
 * The BFF streams the bytes with `Content-Disposition: inline` so
 * the browser renders rather than downloads. 404 when the ticket
 * doesn't exist, belongs to another user, or has no file attached
 * (e.g. metadata-only tickets created before the upload feature).
 */
export const getTicketFile = async (
	token: string,
	id: string
): Promise<{ url: string; contentType: string | null }> => {
	const res = await fetch(`${API_BASE}/${id}/file`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}` }
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	const contentType = res.headers.get('content-type');
	const blob = await res.blob();
	return { url: URL.createObjectURL(blob), contentType };
};

/**
 * Update one ticket's status. Used by the detail screen's
 * "Mark as DONE" / "Mark as CANCELLED" actions. The BFF already
 * supports all status transitions via PATCH; this thin wrapper keeps
 * the wire shape off the call sites.
 *
 * Returns the updated ticket so the caller can refresh its local
 * copy without a second GET.
 */
export const updateTicketStatus = async (
	token: string,
	id: string,
	status: TicketStatus
): Promise<CreatedTicket> => {
	const res = await fetch(`${API_BASE}/${id}/status`, {
		method: 'PATCH',
		headers: {
			authorization: `Bearer ${token}`,
			'content-type': 'application/json',
			accept: 'application/json'
		},
		body: JSON.stringify({ status })
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket;
};

/**
 * Retry a ticket stuck on {@link TicketStatus#ON_ERROR}. PATCHes the
 * status back to {@code OPEN}, which clears the persisted error message
 * (see `Ticket.withStatus` on the BFF) and re-enqueues the ticket for
 * the scheduled extraction pipeline — the next cron tick picks it up
 * and runs the same `processTicket` path that ran the first time.
 *
 * Intended as the dashboard's "Retry" button on ON_ERROR rows. The
 * PATCH is the same wire call as a manual status flip; this wrapper
 * exists so call sites read as intent (`retryTicket`) rather than as
 * the underlying state machine (`updateTicketStatus(id, 'OPEN')`).
 *
 * @returns the freshly-persisted ticket (status now OPEN, errorMessage
 *          cleared). The caller typically refetches the list to reflect
 *          the orchestrator's later attempt outcome.
 */
export const retryTicket = async (token: string, id: string): Promise<CreatedTicket> => {
	return updateTicketStatus(token, id, 'OPEN');
};

/**
 * Patchable subset of {@link CreatedTicket}. Either field is
 * optional in the payload — the BFF only updates the fields the
 * caller actually sent. Used by the detail screen's "Save edits"
 * action.
 */
export type TicketMetadataPatch = {
	title?: string;
	description?: string;
};

/**
 * Update a ticket's user-editable fields (title + description).
 * PATCH {@code /api/tickets/{id}} on the BFF. Returns the full
 * updated ticket; the caller replaces its local copy with this so
 * the `updatedAt` reflects the patch.
 *
 * Throws {@link TicketApiError} on 4xx / 5xx; 404 when the ticket
 * doesn't exist or belongs to another user (mirrors the read paths —
 * never leak existence across tenants).
 */
export const updateTicketMetadata = async (
	token: string,
	id: string,
	patch: TicketMetadataPatch
): Promise<CreatedTicket> => {
	const res = await fetch(`${API_BASE}/${id}`, {
		method: 'PATCH',
		headers: {
			authorization: `Bearer ${token}`,
			'content-type': 'application/json',
			accept: 'application/json'
		},
		body: JSON.stringify(patch)
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as CreatedTicket;
};

/**
 * Editable subset of {@link TicketExtraction}. The AI's audit fields
 * (`model`, `extractedAt`, `rawResponse`, `extractionPayload`) are
 * not in this shape — the BFF's `replace` keeps them server-side
 * untouched so the "extracted by X on Y" attribution stays
 * truthful even after the user corrects a line item.
 */
export type EditableExtraction = {
	merchant: string;
	purchaseDate: string;
	category: string | null;
	products: ExtractedProductLine[];
	totalAmount: number;
	currency: string;
};

/**
 * Replace the editable portion of a ticket's extraction via
 * {@code PUT /api/tickets/{id}/extraction}. Full replacement (not
 * patch) because the fields are deeply interleaved — partial PATCH
 * would need a merge strategy that the AI layer never has to deal
 * with. The wire carries the user-friendly shape; the BFF preserves
 * the AI-only fields.
 *
 * Returns the persisted extraction so the caller can refresh the
 * `model`/`extractedAt` fields too (the BFF re-reads the row and
 * serialises the full shape).
 */
export const replaceTicketExtraction = async (
	token: string,
	id: string,
	next: EditableExtraction
): Promise<TicketExtraction> => {
	const res = await fetch(`${API_BASE}/${id}/extraction`, {
		method: 'PUT',
		headers: {
			authorization: `Bearer ${token}`,
			'content-type': 'application/json',
			accept: 'application/json'
		},
		body: JSON.stringify(next)
	});
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as TicketExtraction;
};

/**
 * Validated read view: shop + lines for a DONE ticket, joined from
 * the catalogue tables ({@code shops}, {@code products},
 * {@code prices}, {@code line_tickets}). Returns {@code null} when
 * the BFF answers 404 — the caller falls through to the JSONB
 * extraction view (ticket exists but isn't validated yet, or
 * the normaliser hasn't run).
 */
export type CatalogueLine = {
	productName: string | null;
	unit: string | null;
	quantity: number | null;
	pricePerUnit: number | null;
	lineTotal: number | null;
};

export type TicketCatalogue = {
	shopId: string;
	shopName: string;
	lines: CatalogueLine[];
};

export const getTicketCatalogue = async (
	token: string,
	id: string
): Promise<TicketCatalogue | null> => {
	const res = await fetch(`${API_BASE}/${id}/catalogue`, {
		method: 'GET',
		headers: { authorization: `Bearer ${token}`, accept: 'application/json' }
	});
	if (res.status === 404) return null;
	if (!res.ok) {
		bubbleAuthExpired(res);
		throw new TicketApiError(await parseError(res), res.status);
	}
	return (await res.json()) as TicketCatalogue;
};
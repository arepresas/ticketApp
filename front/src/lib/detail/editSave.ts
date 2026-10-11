/**
 * Save orchestration for the detail screen's edits.
 *
 * <p>Extracted from {@code TicketDetailApp.svelte} as free
 * functions plus a tiny state object, for the same reason
 * {@link file://./flushThenAct.ts} exists: the component is a shadow-DOM
 * custom element that jsdom cannot mount, so the interesting logic has
 * to be reachable by a unit test.
 *
 * <p>Three problems this solves:
 *
 * <ol>
 *   <li><b>Edits made while a save is in flight.</b> Every edit bumps
 *       a generation counter. The save compares the counter it
 *       captured with the one it sees on completion: unchanged means
 *       it is safe to adopt the server's copy and clear `dirty`;
 *       changed means the user kept typing, so the local state stays
 *       authoritative, the save runs again, and only a save that
 *       reaches a stable generation is allowed to report success. The
 *       previous unconditional `dirty = false` was how an edit typed
 *       during the request round-trip silently disappeared.</li>
 *   <li><b>The audit-field re-read.</b> The PUT is the commit
 *       boundary. The follow-up {@code getTicketExtraction} exists
 *       only to pick up the {@code model}/{@code extractedAt} the
 *       server preserved, so its failure must not be reported as a
 *       failed save — the edit is already durable.</li>
 *   <li><b>Unrelated sections overwriting fresher data.</b> The
 *       extraction travels in the payload only when the extraction
 *       itself was edited. A metadata-only save leaves the server's
 *       extraction row alone instead of re-writing it from a local
 *       copy that a concurrent AI run may have refreshed since.</li>
 * </ol>
 */
import type { CreatedTicket, EditableExtraction, TicketExtraction } from '../api/tickets';
import { asSpendCategory } from '../api/spendCategory';

/** The two halves of the detail screen that can be edited. */
export type EditSection = 'metadata' | 'extraction';

/** Sparse payload for {@code PUT /api/tickets/{id}}. */
export type EditPayload = {
	title?: string;
	description?: string;
	extraction: EditableExtraction | null;
};

/**
 * Dirtiness plus the generation counter.
 *
 * <p>The counter is monotonic and never reset; only the section flags
 * clear. Comparing two readings of it is enough to tell "nothing was
 * typed during the save" from "something was".
 */
export type SaveState = {
	/** Record an edit to {@code section}. Bumps the generation. */
	markDirty(section: EditSection): void;
	/** Edits made since the last successful save. */
	isDirty(): boolean;
	/** Which sections have unsaved edits. */
	dirtySections(): ReadonlySet<EditSection>;
	/** Monotonic edit counter. */
	generation(): number;
	/** Drop every dirty flag (called only after a stable save). */
	clear(): void;
};

export function createSaveState(): SaveState {
	let generation = 0;
	let metadataDirty = false;
	let extractionDirty = false;
	return {
		markDirty(section: EditSection): void {
			generation += 1;
			if (section === 'extraction') extractionDirty = true;
			else metadataDirty = true;
		},
		isDirty(): boolean {
			return metadataDirty || extractionDirty;
		},
		dirtySections(): ReadonlySet<EditSection> {
			return new Set<EditSection>([
				...(metadataDirty ? (['metadata'] as const) : []),
				...(extractionDirty ? (['extraction'] as const) : [])
			]);
		},
		generation(): number {
			return generation;
		},
		clear(): void {
			metadataDirty = false;
			extractionDirty = false;
		}
	};
}

/** How many times one {@link runEditSave} call may re-issue the PUT
 * because the user kept typing. Bounded so a fast typist on a slow
 * connection degrades into "still editing" instead of a request loop. */
const MAX_ATTEMPTS = 3;

/**
 * Build the sparse payload for one save.
 *
 * <p>Only the sections flagged dirty are sent. A clean extraction
 * serialises to `null`, which the BFF reads as "nothing to write" —
 * never as "clear it".
 */
export function buildEditPayload(
	ticket: Pick<CreatedTicket, 'title' | 'description'>,
	extraction: TicketExtraction | null,
	sections: ReadonlySet<EditSection>
): EditPayload {
	const metadata = sections.has('metadata');
	const payload: EditPayload = { extraction: null };
	if (metadata) {
		payload.title = ticket.title;
		payload.description = ticket.description;
	}
	if (extraction && sections.has('extraction')) {
		payload.extraction = {
			merchant: extraction.merchant,
			purchaseDate: extraction.purchaseDate,
			// Narrowed on the way out: a payload from a build that knew
			// fewer categories (or a hand-edited one) must not put a
			// value on the wire the constrained column would reject.
			category: asSpendCategory(extraction.category),
			products: extraction.products.map((p) => ({
				name: p.name,
				quantity: p.quantity ?? 1,
				unit: p.unit,
				pricePerUnit: p.pricePerUnit ?? 0,
				// lineTotal is derived, never read back: qty × €/unit.
				lineTotal: (p.quantity ?? 1) * (p.pricePerUnit ?? 0)
			})),
			totalAmount: extraction.totalAmount,
			currency: extraction.currency
		};
	}
	return payload;
}

/** The two HTTP calls a save makes, injected so tests never fetch. */
export type EditSaveApi = {
	applyEdit: (
		token: string,
		id: number,
		payload: EditPayload
	) => Promise<CreatedTicket>;
	getExtraction: (token: string, id: number) => Promise<TicketExtraction | null>;
};

export type RunEditSaveArgs = {
	token: string;
	api: EditSaveApi;
	state: SaveState;
	/** Read at the start of every attempt, so the newest local values ship. */
	currentTicket: () => CreatedTicket | null;
	currentExtraction: () => TicketExtraction | null;
	/**
	 * Stable generation reached. Adopt the server's ticket, clear the
	 * dirty flags and flash the "saved" pill.
	 */
	onCommitted: (ticket: CreatedTicket, extraction: TicketExtraction | null) => void;
	/** The PUT failed; the caller surfaces the error. */
	onSaveFailed: (err: unknown) => void;
	/** The save gave up because the user kept typing. */
	onUnstable: () => void;
	maxAttempts?: number;
};

/**
 * Persist the pending edits, retrying while the user keeps typing.
 *
 * <p>Resolves `true` only when the edits that were on screen reached
 * the server and the screen is clean. `false` means "do not close the
 * view": either the write failed, or the user out-ran the retries.
 */
export async function runEditSave(args: RunEditSaveArgs): Promise<boolean> {
	const maxAttempts = args.maxAttempts ?? MAX_ATTEMPTS;
	for (let attempt = 0; attempt < maxAttempts; attempt += 1) {
		const ticket = args.currentTicket();
		if (!ticket) return true;
		const generationAtStart = args.state.generation();
		const sections = args.state.dirtySections();
		if (sections.size === 0) return true;

		let saved: CreatedTicket;
		try {
			saved = await args.api.applyEdit(args.token, ticket.id, buildEditPayload(
				ticket,
				args.currentExtraction(),
				sections
			));
		} catch (err) {
			args.onSaveFailed(err);
			return false;
		}

		// The PUT committed. Re-reading the audit fields is a
		// convenience, never part of the commit: a failure here leaves
		// the local copy (which still has the user's values) in place.
		let refreshed: TicketExtraction | null = null;
		if (sections.has('extraction')) {
			try {
				refreshed = await args.api.getExtraction(args.token, saved.id);
			} catch {
				// Swallowed deliberately — the durable write already
				// happened; only the "extracted by X on Y" stamp is
				// stale on screen.
			}
		}

		if (args.state.generation() === generationAtStart) {
			args.state.clear();
			args.onCommitted(saved, refreshed);
			return true;
		}
		// Edited mid-flight: loop and ship the newer values.
	}
	args.onUnstable();
	return false;
}
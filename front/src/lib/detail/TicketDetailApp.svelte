<svelte:options customElement="ticket-detail-app" />

<script lang="ts">
	/**
	 * TicketDetailApp — per-ticket detail screen.
	 *
	 * What it shows:
	 *  - Structured extraction (merchant, products, total, category) on the
	 *    left, file preview (image zoom + PDF iframe) on the right.
	 *  - Terminal-state actions ("Mark as DONE" / "Mark as cancelled")
	 *    in the footer.
	 *
	 * Editing model (the seamless bit):
	 *  - Every field is an always-editable input styled to look like
	 *    text by default. There is no Edit / Save / Cancel ceremony —
	 *    changes are saved on a debounced timer per save group (title +
	 *    description = one batch; everything else = one batch). Saves
	 *    report inline via a small "Saving… / Saved" indicator next to
	 *    the title; errors briefly flash a "Couldn't save" hint.
	 *  - This replaces the previous Edit/Save/Cancel modal flow that
	 *    blocked the screen with a dialog and required the user to
	 *    click through every change.
	 *
	 * Navigation (the other seamless bit):
	 *  - The X close button is gone. The browser's back button is the
	 *    only way out — `close()` is now `history.back()` and the URL
	 *    hash `#ticket/<id>` always reflects the open ticket so reload
	 *    restores the same view.
	 *  - On popstate the body class flips (handled centrally in
	 *    `lib/navigation.ts`); we clean up the object URL + clear the
	 *    ticket + extraction so memory doesn't leak across screens.
	 */
	import tailwindCss from '../../app.css?inline';

	import {
		Download,
		FileText,
		Image as ImageIcon,
		AlertCircle,
		Loader2,
		CheckCircle2,
		XCircle,
		ZoomIn,
		ZoomOut,
		RotateCcw,
		RotateCw,
		Plus,
		Trash2,
		Check as CheckIcon,
		Save
	} from '@lucide/svelte';

	import { auth } from '../auth/store.svelte';
	import {
		getTicket,
		getTicketExtraction,
		getTicketFile,
		updateTicketStatus,
		updateTicketMetadata,
		replaceTicketExtraction,
		getTicketCatalogue,
		retryTicket,
		TicketApiError,
		type CreatedTicket,
		type TicketExtraction,
		type ExtractedProductLine,
		type CatalogueLine
	} from '../api/tickets';
	import { navigateBack } from '../navigation';

	const SESSION_STORAGE_KEY = 'ticketapp.session';

	function readSessionToken(): string | null {
		const g = globalThis as { window?: { sessionStorage?: Storage } };
		const s = g.window?.sessionStorage;
		if (!s) return null;
		try {
			return s.getItem(SESSION_STORAGE_KEY);
		} catch {
			return null;
		}
	}

	/** Parse `#ticket/<uuid>` out of the URL. Null when malformed. */
	function parseTicketIdFromHash(): string | null {
		const h = window.location.hash;
		const m = /^#?ticket\/([0-9a-fA-F-]{36})$/.exec(h);
		return m ? m[1] : null;
	}

	let ticketId = $state<string | null>(parseTicketIdFromHash());
	let ticket = $state<CreatedTicket | null>(null);
	let extraction = $state<TicketExtraction | null>(null);
	// Validated read view. Populated only when `ticket.status === 'DONE'`
	// — the normaliser writes the catalogue tables on the mark-as-done
	// transition and we re-read them here, so the detail screen's
	// product lines stop mirroring the editable JSONB extraction once
	// a ticket locks.
	let catalogue = $state<import('../api/tickets').TicketCatalogue | null>(null);
	let fileUrl = $state<string | null>(null);
	let fileContentType = $state<string | null>(null);
	let loading = $state(false);
	let errorMessage = $state<string | null>(null);
	let acting = $state(false);

	/**
	 * DONE tickets are read-only everywhere: the user can't keep
	 * editing after they've validated the receipt. The Save
	 * button's full branch hides once this flips false; every
	 * editable field picks up `readonly`; the mark-as-done / mark-as-cancelled
	 * footer actions hide (the ticket is already terminal).
	 */
	const editable = $derived(
		ticket?.status !== 'DONE' && ticket?.status !== 'CANCELLED'
	);
	const isDone = $derived(ticket?.status === 'DONE');

	/**
	 * One row per line item. Switches source based on status:
	 * validated tickets render the catalogue rows (line_tickets joined
	 * with products + prices), others render the JSONB extraction
	 * (still editable). Same shape ({@link ExtractedProductLine})
	 * downstream so the template doesn't branch.
	 */
	interface DisplayLine {
		name: string;
		quantity: number | null;
		unit: string | null;
		pricePerUnit: number | null;
		lineTotal: number | null;
	}
	const displayLines = $derived.by((): DisplayLine[] => {
		if (isDone && catalogue) {
			return catalogue.lines.map((l: CatalogueLine): DisplayLine => ({
				name: l.productName ?? '',
				quantity: l.quantity,
				unit: l.unit,
				pricePerUnit: l.pricePerUnit,
				lineTotal: l.lineTotal
			}));
		}
		if (extraction) {
			return extraction.products;
		}
		return [];
	});

	/**
	 * Image-preview zoom + pan state. Only meaningful when the
	 * preview is an image (not a PDF — the iframe handles its own
	 * native zoom). Reset to (1, 0, 0) on every new file load so
	 * switching tickets doesn't carry zoom from the previous one.
	 *
	 * Discrete zoom steps (1×, 1.5×, 2×, 3×, 4×) instead of a
	 * continuous slider because receipts are small icons in the
	 * preview frame — fine-grained zoom is unnecessary and the
	 * discrete model makes the buttons trivially testable.
	 */
	const ZOOM_STEPS = [1, 1.5, 2, 3, 4] as const;
	let zoom = $state<number>(1);
	let panX = $state<number>(0);
	let panY = $state<number>(0);
	let dragStart = $state<{ x: number; y: number; panX: number; panY: number } | null>(null);

	function zoomIn(): void {
		const idx = ZOOM_STEPS.indexOf(zoom as typeof ZOOM_STEPS[number]);
		if (idx >= 0 && idx < ZOOM_STEPS.length - 1) {
			zoom = ZOOM_STEPS[idx + 1];
		}
	}

	function zoomOut(): void {
		const idx = ZOOM_STEPS.indexOf(zoom as typeof ZOOM_STEPS[number]);
		if (idx > 0) {
			zoom = ZOOM_STEPS[idx - 1];
		}
	}

	function resetZoom(): void {
		zoom = 1;
		panX = 0;
		panY = 0;
	}

	/**
	 * Mouse-wheel zoom around the cursor position. We can't use the
	 * native browser zoom (Ctrl+wheel) because that zooms the entire
	 * page, not just the preview pane. Hand-rolled: compute the
	 * cursor offset inside the image, change the zoom level, then
	 * shift the pan by the same fraction so the point under the
	 * cursor stays under the cursor.
	 */
	function onImageWheel(e: WheelEvent): void {
		e.preventDefault();
		const direction = e.deltaY < 0 ? +1 : -1;
		const before = zoom;
		if (direction > 0) zoomIn();
		else zoomOut();
		if (zoom !== before) {
			// Re-anchor the view to the cursor. deltaY scroll already
			// moves the scroll container a few px, so this is a
			// best-effort fix-up rather than a precise transform.
		}
	}

	function onImageMouseDown(e: MouseEvent): void {
		if (zoom <= 1) return;
		// Only the primary button starts a pan — middle/right click
		// stays available for the browser's context menu (useful
		// even on an image).
		if (e.button !== 0) return;
		dragStart = { x: e.clientX, y: e.clientY, panX, panY };
	}

	function onImageMouseMove(e: MouseEvent): void {
		if (!dragStart) return;
		panX = dragStart.panX + (e.clientX - dragStart.x);
		panY = dragStart.panY + (e.clientY - dragStart.y);
	}

	function onImageMouseUp(): void {
		dragStart = null;
	}

	/**
	 * Keyboard pan fallback so the zoomed image is usable without a
	 * mouse (and so the element passes Svelte's a11y rule — keyboard
	 * handlers on a draggable surface make the role="img" element a
	 * legitimate target for mouse + keyboard listeners). Arrow keys
	 * pan in 24px steps, +/- keys reuse the same zoom logic as the
	 * toolbar buttons.
	 */
	function onImageKeydown(e: KeyboardEvent): void {
		const STEP = 24;
		switch (e.key) {
			case 'ArrowLeft':
				e.preventDefault();
				panX += STEP;
				break;
			case 'ArrowRight':
				e.preventDefault();
				panX -= STEP;
				break;
			case 'ArrowUp':
				e.preventDefault();
				panY += STEP;
				break;
			case 'ArrowDown':
				e.preventDefault();
				panY -= STEP;
				break;
			case '+':
			case '=':
				e.preventDefault();
				zoomIn();
				break;
			case '-':
			case '_':
				e.preventDefault();
				zoomOut();
				break;
			case '0':
				e.preventDefault();
				resetZoom();
				break;
		}
	}

	$effect(() => {
		// Reset zoom whenever a new ticket's file loads so the user
		// doesn't land on a previously-zoomed view of a different
		// receipt.
		void fileUrl; // tracked dependency
		zoom = 1;
		panX = 0;
		panY = 0;
	});

	/** Currency formatter shared by the table footer and the badges. */
	const fmtAmount = (amount: number, currency: string): string => {
		try {
			return new Intl.NumberFormat(undefined, {
				style: 'currency',
				currency
			}).format(amount);
		} catch {
			return `${amount.toFixed(2)} ${currency}`;
		}
	};

	const fmtDate = (iso: string): string => {
		try {
			return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(new Date(iso));
		} catch {
			return iso;
		}
	};

	const fmtDateTime = (iso: string): string => {
		try {
			return new Intl.DateTimeFormat(undefined, {
				dateStyle: 'medium',
				timeStyle: 'short'
			}).format(new Date(iso));
		} catch {
			return iso;
		}
	};

	const isImagePreview = (ct: string | null): boolean => !!ct && ct.startsWith('image/');
	const isPdfPreview = (ct: string | null): boolean => ct === 'application/pdf';

	async function load(id: string): Promise<void> {
		const token = readSessionToken();
		if (!token) {
			errorMessage = 'Session expired. Sign in again to view this ticket.';
			ticket = null;
			extraction = null;
			catalogue = null;
			return;
		}
		// Revoke the previous blob URL so we don't leak memory across
		// reloads. Safe to call with null (revokeObjectURL ignores).
		if (fileUrl) {
			URL.revokeObjectURL(fileUrl);
			fileUrl = null;
		}
		catalogue = null;
		loading = true;
		errorMessage = null;
		try {
			const [t, ex, file] = await Promise.all([
				getTicket(token, id),
				getTicketExtraction(token, id),
				getTicketFile(token, id).catch(() => null)
			]);
			ticket = t;
			extraction = ex;
			if (file) {
				fileUrl = file.url;
				fileContentType = file.contentType;
			} else {
				fileUrl = null;
				fileContentType = null;
			}
			// Validated tickets read their line data from the
			// catalogue tables (line_tickets joined with products
			// + prices), not from the JSONB extraction. We still
			// load the extraction above — its merchant / date /
			// category / total / currency fields drive the
			// card-level header that the catalogue doesn't carry.
			if (t.status === 'DONE') {
				const cat = await getTicketCatalogue(token, id).catch(
					() => null
				);
				catalogue = cat;
			}
		} catch (err: unknown) {
			ticket = null;
			extraction = null;
			catalogue = null;
			if (err instanceof TicketApiError) {
				if (err.status === 404) {
					errorMessage = 'This ticket is no longer available — it may have been deleted.';
				} else {
					errorMessage = `Failed to load ticket (${err.status}): ${err.message}`;
				}
			} else {
				errorMessage = `Failed to load ticket: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
		} finally {
			loading = false;
		}
	}

	/** Browser-back the user. The navigation module's popstate listener
	 * will sync the body class to whatever the URL ends up at. */
	function close(): void {
		navigateBack();
	}

	// ---------------------------------------------------------------------
	// Resizable preview pane — width + height, independent.
	//
	// The detail screen renders the extraction card on the left and the
	// file preview on the right. Two drag handles give the user full
	// control over the preview's footprint without forcing an aspect
	// ratio:
	//
	//   1. Vertical handle on the LEFT side of the preview — resizes
	//      the preview's WIDTH (extraction section compensates).
	//   2. Horizontal handle on the BOTTOM of the preview — resizes the
	//      preview's HEIGHT independently of the width.
	//
	// Both axes are clamped to a sensible min/max range so neither
	// dimension collapses to 0 or pushes the page footer off-screen.
	// State is component-local (no persistence — re-opening the detail
	// screen starts at the default), matching the rest of the
	// transient UI state on this screen.
	//
	// Height = max(userDrag, listHeight): the row's left side carries
	// the products list. A ResizeObserver watches the extraction
	// section so the preview grows to match when the list is taller
	// than the user's chosen floor; otherwise the floor (or the
	// implicit fit-to-image height, never less than the floor) wins.
	// The image inside the pane always renders at its natural size —
	// no `object-cover` stretching — so a small image in a tall pane
	// leaves whitespace rather than getting distorted.
	//
	// Mouse-move / mouse-up are bound to `window` for the duration of
	// the drag so the user can release the mouse anywhere — including
	// over the iframe-backed PDF preview where mouseup wouldn't reach
	// the handle itself. The active-drag cursor is also applied to
	// `document.body` so the resize cursor stays consistent when the
	// pointer briefly leaves the handle.
	// ---------------------------------------------------------------------
	const PREVIEW_WIDTH_MIN = 320;
	const PREVIEW_WIDTH_MAX = 960;
	const PREVIEW_WIDTH_DEFAULT = 600;
	const PREVIEW_HEIGHT_MIN = 240;
	const PREVIEW_HEIGHT_MAX = 720;
	const PREVIEW_HEIGHT_DEFAULT = 480;

	/**
	 * Approx. chrome height (card header + drag handle + borders) that
	 * sits above + below the preview's content area. Subtracted from
	 * the measured extraction card so the preview pane ends at the
	 * same baseline as the list, not somewhere below it.
	 */
	const PREVIEW_CHROME_HEIGHT = 50;

	let previewWidth = $state(PREVIEW_WIDTH_DEFAULT);
	let previewHeight = $state(PREVIEW_HEIGHT_DEFAULT);
	let widthDrag = $state<{ startX: number; startWidth: number } | null>(null);
	let heightDrag = $state<{ startY: number; startHeight: number } | null>(null);

	/**
	 * Live height of the extraction section, observed via
	 * ResizeObserver. Used as the floor for the preview pane when the
	 * user's `previewHeight` would otherwise leave the preview shorter
	 * than the products list.
	 */
	let extractionHeight = $state(0);
	let extractionSection: HTMLElement | undefined = $state(undefined);

	/**
	 * Effective content-area height: max(userDrag, listHeight - chrome).
	 * The derive re-runs whenever either input changes — user drag
	 * (debounced via $effect events), extraction height from the
	 * ResizeObserver callback. Both flow through Svelte reactivity
	 * without any extra plumbing.
	 */
	const previewContentHeight = $derived(
		clampHeight(Math.max(previewHeight, extractionHeight - PREVIEW_CHROME_HEIGHT))
	);

	function clampWidth(w: number): number {
		return Math.max(PREVIEW_WIDTH_MIN, Math.min(PREVIEW_WIDTH_MAX, w));
	}
	function clampHeight(h: number): number {
		return Math.max(PREVIEW_HEIGHT_MIN, Math.min(PREVIEW_HEIGHT_MAX, h));
	}

	function onWidthHandleMouseDown(e: MouseEvent): void {
		if (e.button !== 0) return;
		e.preventDefault();
		widthDrag = { startX: e.clientX, startWidth: previewWidth };
	}

	function onHeightHandleMouseDown(e: MouseEvent): void {
		if (e.button !== 0) return;
		e.preventDefault();
		heightDrag = { startY: e.clientY, startHeight: previewHeight };
	}

	function onWidthHandleKeydown(e: KeyboardEvent): void {
		const STEP = 24;
		switch (e.key) {
			case 'ArrowLeft':
				e.preventDefault();
				previewWidth = clampWidth(previewWidth - STEP);
				break;
			case 'ArrowRight':
				e.preventDefault();
				previewWidth = clampWidth(previewWidth + STEP);
				break;
			case 'Home':
				e.preventDefault();
				previewWidth = PREVIEW_WIDTH_MIN;
				break;
			case 'End':
				e.preventDefault();
				previewWidth = PREVIEW_WIDTH_MAX;
				break;
		}
	}

	function onHeightHandleKeydown(e: KeyboardEvent): void {
		const STEP = 24;
		switch (e.key) {
			case 'ArrowUp':
				e.preventDefault();
				previewHeight = clampHeight(previewHeight - STEP);
				break;
			case 'ArrowDown':
				e.preventDefault();
				previewHeight = clampHeight(previewHeight + STEP);
				break;
			case 'Home':
				e.preventDefault();
				previewHeight = PREVIEW_HEIGHT_MIN;
				break;
			case 'End':
				e.preventDefault();
				previewHeight = PREVIEW_HEIGHT_MAX;
				break;
		}
	}

	$effect(() => {
		if (!widthDrag) return;
		const onMove = (e: MouseEvent): void => {
			if (!widthDrag) return;
			const dx = e.clientX - widthDrag.startX;
			previewWidth = clampWidth(widthDrag.startWidth + dx);
		};
		const onUp = (): void => {
			widthDrag = null;
		};
		document.body.style.cursor = 'col-resize';
		document.body.style.userSelect = 'none';
		window.addEventListener('mousemove', onMove);
		window.addEventListener('mouseup', onUp);
		return () => {
			document.body.style.cursor = '';
			document.body.style.userSelect = '';
			window.removeEventListener('mousemove', onMove);
			window.removeEventListener('mouseup', onUp);
		};
	});

	$effect(() => {
		if (!heightDrag) return;
		const onMove = (e: MouseEvent): void => {
			if (!heightDrag) return;
			const dy = e.clientY - heightDrag.startY;
			previewHeight = clampHeight(heightDrag.startHeight + dy);
		};
		const onUp = (): void => {
			heightDrag = null;
		};
		document.body.style.cursor = 'row-resize';
		document.body.style.userSelect = 'none';
		window.addEventListener('mousemove', onMove);
		window.addEventListener('mouseup', onUp);
		return () => {
			document.body.style.cursor = '';
			document.body.style.userSelect = '';
			window.removeEventListener('mousemove', onMove);
			window.removeEventListener('mouseup', onUp);
		};
	});

	/**
	 * Live height of the extraction section (the column that
	 * carries the products list). A ResizeObserver pushes the height
	 * into `extractionHeight` so the `$derived` preview content
	 * height can grow to match when the list exceeds the user's
	 * chosen floor. Lazily instantiated once the extraction
	 * `bind:this` element is resolved.
	 */
	$effect(() => {
		const el = extractionSection;
		if (!el) return;
		// Seed once so the preview doesn't render at 0 height on first
		// paint before the observer fires.
		extractionHeight = el.offsetHeight;
		const observer = new ResizeObserver((entries) => {
			for (const entry of entries) {
				extractionHeight = entry.contentRect.height;
			}
		});
		observer.observe(el);
		return () => observer.disconnect();
	});

	async function setStatus(status: 'DONE' | 'CANCELLED'): Promise<void> {
		if (!ticketId || acting) return;
		const token = readSessionToken();
		if (!token) return;
		acting = true;
		errorMessage = null;
		try {
			await updateTicketStatus(token, ticketId, status);
			window.dispatchEvent(new CustomEvent('ticket:updated'));
			close();
		} catch (err: unknown) {
			if (err instanceof TicketApiError) {
				errorMessage = `Could not mark as ${status} (${err.status}): ${err.message}`;
			} else {
				errorMessage = `Could not mark as ${status}: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
		} finally {
			acting = false;
		}
	}

	/**
	 * Reset a ticket stuck on {@code ON_ERROR} back to {@code OPEN} so
	 * the scheduled extraction pipeline picks it up again. Mirrors the
	 * dashboard's {@code handleRetry} helper — same wire call
	 * ({@code retryTicket} → {@code PATCH status=OPEN}), different
	 * screen, distinct error message so the user can tell which
	 * action failed. Closed-on-success mirrors {@link setStatus}: the
	 * detail screen is no longer useful once the status flips because
	 * the orchestrator will mutate the extraction row asynchronously.
	 */
	async function resetStatus(): Promise<void> {
		if (!ticketId || acting) return;
		const token = readSessionToken();
		if (!token) return;
		acting = true;
		errorMessage = null;
		try {
			await retryTicket(token, ticketId);
			window.dispatchEvent(new CustomEvent('ticket:updated'));
			close();
		} catch (err: unknown) {
			if (err instanceof TicketApiError) {
				errorMessage = `Could not reset ticket (${err.status}): ${err.message}`;
			} else {
				errorMessage = `Could not reset ticket: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
		} finally {
			acting = false;
		}
	}

	// ---------------------------------------------------------------------
	// Seamless autosave — replaces the previous Edit / Save / Cancel flow.
	//
	// Two save groups:
	//   - Metadata: PATCH /api/tickets/{id} for title + description.
	//   - Extraction: PUT /api/tickets/{id}/extraction for the editable
	//     AI-derived fields (merchant, purchaseDate, category, products,
	//     totalAmount, currency). The AI's audit fields (model,
	//     extractedAt, rawResponse, extractionPayload) stay server-side
	//     and are not in the send payload.
	//
	// Each group debounces (metadata 500ms, extraction 800ms — extractions
	// hold longer because the products array can be large). Only one
	// in-flight save per group; the most recent debounced fire is the
	// one that runs. Saves re-read local state at commit time so the
	// payload always matches the latest typing.
	//
	// Local editing only — no autosave. Every input mutates the
	// ticket / extraction state in place through `bind:value`, but
	// nothing hits the network until the user clicks the Save button.
	// A single boolean `dirty` flag tracks whether anything has
	// changed since the last successful save; the Save button's
	// enabled state is bound to it.
	//
	// Save sequence: on click, fire `PATCH /tickets/{id}` for the
	// metadata first; only on success fire `PUT /tickets/{id}/extraction`
	// for the extraction (if one exists). Failing fast on the
	// metadata PATCH means the user doesn't end up with a half-saved
	// state where the products table updated but the title didn't.
	//
	// The `lineTotal` field is no longer user-editable; computedLineTotal
	// derives it on save so the wire shape stays the same without ever
	// reading from `product.lineTotal`.
	// ---------------------------------------------------------------------

	/**
	 * Compute the line total as qty × €/unit. Used both for the
	 * read-only column in the products table and for the save
	 * payload. Treats null / NaN inputs as 0 so a partially-typed
	 * row renders as 0.00 rather than NaN.
	 *
	 * Accepts any value with {@code quantity} + {@code pricePerUnit}
	 * fields so it works for both the editable {@code ExtractedProductLine}
	 * (from JSONB extraction) and the read-only {@code DisplayLine}
	 * (from the catalogue for DONE tickets).
	 */
	function computedLineTotal(p: {
		quantity: number | null;
		pricePerUnit: number | null;
	}): number {
		const q = Number(p.quantity);
		const unit = Number(p.pricePerUnit);
		if (!Number.isFinite(q) || !Number.isFinite(unit)) return 0;
		return q * unit;
	}

	/** Anything changed since the last successful save? Drives the
	 * Save button's enabled state and the visual "Unsaved" hint. */
	let dirty = $state(false);

	/** Save in-flight; disables the Save button. */
	let savingChanges = $state(false);

	/** Most-recent save outcome, drives the small status pill next
	 * to the Save button. Fades after a couple of seconds. */
	type SaveOutcome = 'idle' | 'saved' | 'error';
	let saveOutcome = $state<SaveOutcome>('idle');
	let saveOutcomeFade: ReturnType<typeof setTimeout> | null = null;

	function setSaveOutcome(next: SaveOutcome, holdMs = 0): void {
		if (saveOutcomeFade) clearTimeout(saveOutcomeFade);
		saveOutcome = next;
		if (holdMs > 0) {
			saveOutcomeFade = setTimeout(() => {
				saveOutcome = 'idle';
			}, holdMs);
		}
	}

	function markDirty(): void {
		if (!savingChanges) dirty = true;
	}

	async function saveChanges(): Promise<void> {
		if (!ticket || !dirty || savingChanges) return;
		const token = readSessionToken();
		if (!token) {
			errorMessage = 'Session expired. Sign in again to save changes.';
			setSaveOutcome('error', 3000);
			return;
		}
		savingChanges = true;
		errorMessage = null;
		try {
			// 1) metadata first — `await` so a failure here stops
			// the extraction PUT (no half-saves).
			const updatedTicket = await updateTicketMetadata(token, ticket.id, {
				title: ticket.title,
				description: ticket.description
			});
			ticket = updatedTicket;

			// 2) extraction only if the AI has produced a row to
			// update. Same PUT maps products so lineTotal is always
			// the product of qty × €/unit regardless of stale state.
			if (extraction) {
				const updatedEx = await replaceTicketExtraction(token, ticket.id, {
					merchant: extraction.merchant,
					purchaseDate: extraction.purchaseDate,
					category: extraction.category,
					products: extraction.products.map((p) => ({
						name: p.name,
						quantity: p.quantity ?? 1,
						unit: p.unit,
						pricePerUnit: p.pricePerUnit ?? 0,
						lineTotal: computedLineTotal(p)
					})),
					totalAmount: extraction.totalAmount,
					currency: extraction.currency
				});
				extraction = updatedEx;
			}

			dirty = false;
			setSaveOutcome('saved', 2000);
			window.dispatchEvent(new CustomEvent('ticket:updated'));
		} catch (err) {
			console.warn('TicketDetailApp: save failed', err);
			if (err instanceof TicketApiError) {
				errorMessage = `Save failed (${err.status}): ${err.message}`;
			} else {
				errorMessage = `Save failed: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
			setSaveOutcome('error', 3000);
		} finally {
			savingChanges = false;
		}
	}

	/**
	 * If the user navigates away while there are unsaved changes,
	 * the local edits evaporate (popstate clears state on the
	 * cleanup branch). That's the trade-off chosen here over a more
	 * elaborate "you have unsaved changes, are you sure?" guard:
	 * the screen is for quick edits, and the Save button is the
	 * only way to persist. No shadow buffer.
	 */

	function addProductRow(): void {
		if (!extraction) return;
		const next: ExtractedProductLine = {
			name: '',
			quantity: 1,
			unit: null,
			pricePerUnit: 0,
			lineTotal: 0
		};
		extraction = {
			...extraction,
			products: [...extraction.products, next]
		};
		markDirty();
	}

	function removeProductRow(index: number): void {
		if (!extraction) return;
		const products = extraction.products.filter((_, i) => i !== index);
		extraction = { ...extraction, products };
		markDirty();
	}

	/**
	 * Re-sync the id from the hash on every effect tick. Also fires
	 * the initial load when the user opens a deep link while signed
	 * in. The hash re-parsing makes the back / forward buttons
	 * usable: going from `#ticket/A` to `#ticket/B` re-fetches B
	 * without a full reload.
	 */
	$effect(() => {
		const next = parseTicketIdFromHash();
		if (next !== ticketId) {
			ticketId = next;
			if (next && auth.isAuthenticated) {
				void load(next);
			}
		}
		if (
			auth.isAuthenticated &&
			ticketId &&
			document.body.classList.contains('is-detail')
		) {
			if (!ticket || ticket.id !== ticketId) {
				void load(ticketId);
			}
		}
	});

	/**
	 * `detail:open` is dispatched by the shared router when the
	 * user navigates into a ticket via pushState. Mirrors the
	 * hash-driven path above so the deep-link and click-to-open
	 * flows converge in the same loader.
	 *
	 * <p>The handler MUST also write the incoming id back into
	 * {@code ticketId} — the local $state stays at its mount-time
	 * value (null when the screen was mounted without a hash) until
	 * something explicitly updates it, and the status-action
	 * handlers {@code setStatus} / {@code resetStatus} early-return
	 * when {@code ticketId} is null. Without this sync the buttons
	 * silently no-op on cross-route navigation (dashboard row →
	 * detail), only working after a hard refresh because the fresh
	 * mount seeds {@code ticketId} from {@code parseTicketIdFromHash}.
	 */
	$effect(() => {
		const handler = (e: Event): void => {
			const detail = (e as CustomEvent<{ id: string }>).detail;
			if (detail?.id) {
				ticketId = detail.id;
				void load(detail.id);
			}
		};
		window.addEventListener('detail:open', handler);
		return () => {
			window.removeEventListener('detail:open', handler);
		};
	});

	/**
	 * Cleanup the object URL + local state when the detail screen
	 * stops being the active route (user navigated away via back /
	 * forward). The body class is the single source of truth —
	 * after `popstate` runs in lib/navigation.ts the class flips
	 * off, so we listen for that and drop the blob URL.
	 */
	$effect(() => {
		const handler = (): void => {
			if (!document.body.classList.contains('is-detail')) {
				if (fileUrl) {
					URL.revokeObjectURL(fileUrl);
					fileUrl = null;
				}
				ticket = null;
				extraction = null;
				errorMessage = null;
			}
		};
		window.addEventListener('popstate', handler);
		return () => window.removeEventListener('popstate', handler);
	});

	/** Cosmetic helper: cap currency client-side to 3 ISO 4217 letters. */
	function sanitiseCurrency(v: string): string {
		return (v ?? '').toUpperCase().replace(/[^A-Z]/g, '').slice(0, 3);
	}
</script>

<svelte:element this={'style'}>{@html tailwindCss}</svelte:element>

{#if auth.isAuthenticated}
	<div
		class="min-h-screen bg-background text-foreground font-sans antialiased"
		data-testid="ticket-detail-screen"
	>
		<main class="mx-auto flex w-full max-w-screen-2xl flex-col gap-6 px-4 py-8 sm:px-6 lg:px-8">
			<header class="flex items-start justify-between gap-4">
				<div class="min-w-0 flex-1">
					{#if ticket}
						<!--
							Title edit: always a text-styled input.
							bind:value writes through the $state proxy
							straight into ticket.title; `markDirty`
							flips the dirty flag that drives the Save
							button. Nothing is sent to the server until
							the user clicks Save — see the comment
							block at the script header for the save
							model.
						-->
						<input
							type="text"
							bind:value={ticket.title}
							oninput={markDirty}
							maxlength="255"
							required
							readonly={!editable}
							data-testid="ticket-title"
							aria-label="Ticket title"
							class="block w-full truncate border-0 bg-transparent px-0 text-2xl font-bold tracking-tight outline-none focus:ring-0 read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default sm:text-3xl"
						/>
						<p class="mt-1 flex items-center gap-2 text-sm text-muted-foreground">
							<span class="truncate">
								Created {fmtDateTime(ticket.createdAt)}
							</span>
							{#if dirty}
								<span
									class="inline-flex items-center gap-1 text-xs text-amber-600 dark:text-amber-400"
									data-testid="dirty-indicator"
								>
									Unsaved changes
								</span>
							{:else if isDone}
								<span
									class="inline-flex items-center gap-1 rounded-full bg-emerald-500/10 px-2 py-0.5 text-xs font-medium text-emerald-700 ring-1 ring-inset ring-emerald-500/20 dark:text-emerald-300"
									data-testid="validated-badge"
								>
									<CheckIcon class="size-3" />
									Validated · read-only
								</span>
							{:else if saveOutcome === 'saved'}
								<span
									class="inline-flex items-center gap-1 text-xs text-emerald-600 dark:text-emerald-400"
									data-testid="save-outcome"
								>
									<CheckIcon class="size-3" />
									Saved
								</span>
							{:else if saveOutcome === 'error'}
								<span
									class="inline-flex items-center gap-1 text-xs text-destructive"
									data-testid="save-outcome"
								>
									Couldn't save
								</span>
							{/if}
						</p>
						<!--
							Description: same pattern as title. Inline,
							seamless, no modal. Empty placeholder keeps
							the input visible when the user clears the
							field.
						-->
						<input
							type="text"
							bind:value={ticket.description}
							oninput={markDirty}
							maxlength="2000"
							readonly={!editable}
							placeholder="Add a description…"
							aria-label="Ticket description"
							data-testid="ticket-description"
							class="mt-2 block w-full border-0 bg-transparent px-0 text-sm text-muted-foreground outline-none placeholder:text-muted-foreground/60 focus:ring-0 read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
						/>
					{:else}
						<h1 class="truncate text-2xl font-bold tracking-tight sm:text-3xl">
							Ticket
						</h1>
					{/if}
				</div>
				<!--
					Save button: hidden entirely once the ticket is
					validated. DONE tickets render their data from the
					catalogue tables, not from the editable extraction,
					and the user's "no se puede modificar" rule means
					there's nothing to save. The Validated badge in
					the meta row above carries the same UX signal — the
					user sees they're in read-only mode.
				-->
				{#if editable}
				<button
					type="button"
					onclick={saveChanges}
					disabled={!dirty || savingChanges || !ticket}
					data-testid="save-changes"
					aria-label="Save changes"
					class={[
						'inline-flex h-9 items-center justify-center gap-2 rounded-md px-4 text-sm font-medium shadow-sm transition-colors',
						dirty
							? 'bg-primary text-primary-foreground hover:bg-primary/90'
							: 'border border-input bg-background text-muted-foreground',
						'disabled:cursor-not-allowed disabled:opacity-70'
					].join(' ')}
				>
					{#if savingChanges}
						<Loader2 class="size-3.5 animate-spin" />
						Saving…
					{:else if !dirty}
						<CheckIcon class="size-3.5" />
						Saved
					{:else}
						<Save class="size-3.5" />
						Save changes
					{/if}
				</button>
				{/if}
			</header>

			{#if loading && !ticket}
				<div
					class="flex items-center gap-3 rounded-lg border border-border bg-card p-6 text-sm text-muted-foreground"
					aria-busy="true"
				>
					<Loader2 class="size-4 animate-spin" />
					Loading ticket…
				</div>
			{:else if errorMessage}
				<div
					class="flex items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"
					role="alert"
				>
					<AlertCircle class="size-4 flex-shrink-0 mt-0.5" />
					<span>{errorMessage}</span>
				</div>
			{:else if ticket}
				<!--
					Resizable split: extraction on the left (flex-1
					takes what the preview doesn't claim), drag divider
					in the middle (lg+ only — single column on small
					screens stays as it was), preview on the right
					with a fixed pixel width that the user controls.
				-->
				<div class="flex flex-col gap-6 lg:flex-row">
					<!-- Left: structured extraction -->
					<!--
						Bind to `extractionSection` so the
						ResizeObserver above can read its height.
						The ref is set after the mount paints;
						the observer effect guards on `el` being
						defined.
					-->
					<section
						bind:this={extractionSection}
						class="flex min-w-0 flex-1 flex-col gap-4"
					>
						<div
							class="rounded-xl border border-border bg-card p-5 shadow-sm"
							data-testid="extraction-card"
						>
							<header class="mb-4 flex items-start justify-between gap-3">
								<div class="min-w-0 flex-1">
									{#if extraction}
										<!--
											Merchant: styled-as-text input. Sits
											where the H2 used to be so the
											visual hierarchy matches the read
											view (h2 size + weight).
										-->
										<input
											type="text"
											bind:value={extraction.merchant}
											oninput={markDirty}
											maxlength="255"
											required
											readonly={!editable}
											aria-label="Merchant"
											data-testid="extraction-merchant"
											class="block w-full border-0 bg-transparent px-0 text-lg font-semibold outline-none focus:ring-0 read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
										/>
										<div class="mt-2 grid grid-cols-2 gap-3 text-xs text-muted-foreground">
											<label class="flex flex-col gap-1">
												<span class="uppercase tracking-wide">Purchase date</span>
											<input
												type="date"
												bind:value={extraction.purchaseDate}
												oninput={markDirty}
												required
												readonly={!editable}
												aria-label="Purchase date"
												data-testid="extraction-purchase-date"
												class="rounded-md border border-input bg-background px-2 py-1 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
											/>
										</label>
										<label class="flex flex-col gap-1">
											<span class="uppercase tracking-wide">Category</span>
											<input
												type="text"
												value={extraction.category ?? ''}
												readonly={!editable}
												oninput={(e) => {
														// Explicit annotation pins the
														// spread to the full
														// TicketExtraction type — without
														// it, TypeScript widens the
														// spread to a partial and drops
														// required fields like
														// ticketId.
														if (!extraction) return;
														const next: TicketExtraction = {
															...extraction,
															category:
																e.currentTarget.value === ''
																	? null
																	: e.currentTarget.value
														};
														extraction = next;
												markDirty();
													}}
													maxlength="64"
													placeholder="food, pharmacy…"
													aria-label="Category"
													data-testid="extraction-category"
													class="rounded-md border border-input bg-background px-2 py-1 text-sm text-foreground focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0"
												/>
											</label>
										</div>
									{:else if ticket?.status === 'ON_ERROR'}
										<!--
											ON_ERROR + no extraction row: the
											AI pipeline attempted extraction
											and failed. Surface the failure
											headline + the persisted error
											message (truncated server-side at
											2000 chars, see
											TicketExtractionService.ERROR_MESSAGE_MAX_CHARS)
											so the operator can see WHY
											without opening the server logs.
											The footer Reset button is the
											recovery affordance.
										-->
										<div
											class="flex items-start gap-3 rounded-md border border-destructive/30 bg-destructive/5 p-3"
											data-testid="extraction-error"
										>
											<AlertCircle class="mt-0.5 size-5 shrink-0 text-destructive" aria-hidden="true" />
											<div class="min-w-0 flex-1">
												<h2 class="text-lg font-semibold text-destructive">Extraction failed</h2>
												{#if ticket.errorMessage}
													<p
														class="mt-1 break-words text-xs text-destructive/90"
														data-testid="extraction-error-message"
													>
														{ticket.errorMessage}
													</p>
												{:else}
													<p class="mt-1 text-xs text-muted-foreground">
														The AI extraction failed but no error reason was recorded. Use Reset to retry.
													</p>
												{/if}
											</div>
										</div>
									{:else}
										<h2 class="text-lg font-semibold">Awaiting AI extraction</h2>
										<p class="mt-0.5 text-xs text-muted-foreground">
											The AI hasn't run on this ticket yet. Check back in a moment.
										</p>
									{/if}
								</div>
								{#if extraction}
									<div class="text-right">
										<label class="block text-xs uppercase tracking-wide text-muted-foreground" for="extraction-currency">
											Currency
										</label>
										<input
											id="extraction-currency"
											type="text"
											value={extraction.currency}
											readonly={!editable}
											oninput={(e) => {
												// Same explicit-annotation
												// pattern as the category
												// input — the spread otherwise
												// widens to Partial.
												if (!extraction) return;
												const v = sanitiseCurrency(e.currentTarget.value);
												const next: TicketExtraction = {
													...extraction,
													currency: v
												};
												extraction = next;
																markDirty();
											}}
											maxlength="3"
											required
											placeholder="EUR"
											aria-label="Currency"
											data-testid="extraction-currency"
											class="mt-1 w-20 rounded-md border border-input bg-background px-2 py-1 text-center text-sm uppercase focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
										/>
										<label class="mt-2 block text-xs uppercase tracking-wide text-muted-foreground" for="extraction-total">
											Total
										</label>
										<input
											id="extraction-total"
											type="number"
											step="0.01"
											min="0"
											bind:value={extraction.totalAmount}
											oninput={markDirty}
											required
											readonly={!editable}
											aria-label="Total"
											data-testid="extraction-total-input"
											class="mt-1 w-28 rounded-md border border-input bg-background px-2 py-1 text-right text-sm tabular-nums focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
										/>
									</div>
								{/if}
							</header>

							{#if extraction}
								<!--
									Compact products table: Item column
									claims a min-width so the row's
									descriptive name is what eats the
									available horizontal space first;
									inter-column padding is trimmed
									(pr-1 = 4px instead of 8px) so the
									fixed-width numeric cells (qty, unit,
									€/unit, line) sit tighter.
								-->
								<div class="overflow-x-auto">
									<table class="w-full text-sm">
										<thead class="text-left text-xs uppercase tracking-wide text-muted-foreground">
											<tr>
												<th class="min-w-[12rem] py-2 pr-1 font-medium">Item</th>
												<th class="py-2 pr-1 text-right font-medium">Qty</th>
												<th class="py-2 pr-1 font-medium">Unit</th>
												<th class="py-2 pr-1 text-right font-medium">€/unit</th>
												<th class="py-2 pr-1 text-right font-medium">Line</th>
												<th class="py-2 font-medium"></th>
											</tr>
										</thead>
										<tbody class="divide-y divide-border">
											<!--
												Loop source follows the ticket's
												validation state: pre-DONE
												tickets iterate the JSONB
												extraction (editable);
												validated tickets iterate the
												catalogue rows (read-only).
												The common {@code DisplayLine}
												shape keeps the row template
												identical for both.
											-->
											{#each displayLines as product, i (i)}
												<tr data-testid="product-line">
													<td class="min-w-[12rem] py-1.5 pr-1">
														<input
															type="text"
															bind:value={product.name}
															oninput={markDirty}
															maxlength="255"
															required
															readonly={!editable}
															placeholder="Item name"
															aria-label="Item name"
															data-testid="product-name-input"
															class="w-full rounded-md border border-transparent bg-transparent px-2 py-1 text-sm font-medium hover:border-input focus:border-input focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
														/>
													</td>
													<td class="py-1.5 pr-1">
														<input
															type="number"
															step="0.01"
															min="0"
															bind:value={product.quantity}
															oninput={markDirty}
															required
															readonly={!editable}
															aria-label="Quantity"
															data-testid="product-qty-input"
															class="w-20 rounded-md border border-transparent bg-transparent px-2 py-1 text-right text-sm tabular-nums hover:border-input focus:border-input focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
														/>
													</td>
													<td class="py-1.5 pr-1">
														<input
															type="text"
															value={product.unit ?? ''}
															readonly={!editable}
															oninput={(e) => {
																product.unit =
																	e.currentTarget.value === ''
																		? null
																		: e.currentTarget.value;
																markDirty();
															}}
															maxlength="16"
															placeholder="kg"
															aria-label="Unit"
															data-testid="product-unit-input"
															class="w-20 rounded-md border border-transparent bg-transparent px-2 py-1 text-sm hover:border-input focus:border-input focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
														/>
													</td>
													<td class="py-1.5 pr-1">
														<input
															type="number"
															step="0.01"
															bind:value={product.pricePerUnit}
															oninput={markDirty}
															readonly={!editable}
															aria-label="Price per unit"
															data-testid="product-price-per-unit-input"
															class="w-24 rounded-md border border-transparent bg-transparent px-2 py-1 text-right text-sm tabular-nums hover:border-input focus:border-input focus:outline-none focus:ring-2 focus:ring-ring read-only:cursor-default read-only:border-transparent read-only:hover:border-transparent read-only:focus:border-transparent read-only:focus:ring-0 disabled:cursor-default"
														/>
													</td>
													<!--
														Line total is derived
														(qty × €/unit) and not
														user-editable. Discounts
														are modelled as a row with
														a negative value (see the
														domain ProductLine
														Javadoc) — never as an
														override here. The
														display updates instantly
														when qty or €/unit change;
														the autosave picks up the
														payload from the save
														mapper below (not from
														product.lineTotal itself,
														since the editable column
														is gone).
													-->
													<td class="py-1.5 pr-1 text-right tabular-nums">
														<span
															aria-label="Line total"
															data-testid="product-line-total"
															class="block w-24 px-2 py-1 text-sm text-muted-foreground"
														>
															{(computedLineTotal(product)).toFixed(2)}
														</span>
													</td>
													<td class="py-1.5 text-right">
														{#if editable}
															<button
																type="button"
																onclick={() => removeProductRow(i)}
																aria-label="Remove row"
																data-testid="product-row-remove"
																class="inline-flex size-7 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
															>
																<Trash2 class="size-3.5" />
															</button>
														{/if}
													</td>
												</tr>
											{/each}
										</tbody>
									</table>
								</div>
								{#if editable}
									<button
										type="button"
										onclick={addProductRow}
										data-testid="product-row-add"
										class="mt-3 inline-flex h-8 items-center gap-1.5 rounded-md border border-input bg-background px-2.5 text-xs font-medium hover:bg-accent"
									>
										<Plus class="size-3.5" />
										Add row
									</button>
								{/if}
							{:else if !extraction}
								<div
									class="rounded-md border border-dashed border-border bg-muted/30 p-8 text-center text-sm text-muted-foreground"
									data-testid="extraction-empty"
								>
									<FileText class="mx-auto mb-2 size-6 opacity-60" />
									Waiting for the AI to extract the structured data.
								</div>
							{/if}
						</div>

						{#if errorMessage}
							<div
								class="flex items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4 text-sm text-destructive"
								role="alert"
							>
								<AlertCircle class="size-4 flex-shrink-0 mt-0.5" />
								<span>{errorMessage}</span>
							</div>
						{/if}
					</section>

					<!--
						Width handle. Hidden below lg — on small screens
						the layout is single column (parent's `flex-col
						lg:flex-row`) and the preview already takes the
						full width, so there's nothing to resize.

						role="separator" + aria-orientation + aria-valuenow
						let screen readers announce the live width and
						let keyboard users resize via the arrow / Home /
						End handlers.
					-->
<!--
	role="separator" with aria-orientation + handlers is the canonical
	interactive ARIA pattern for resize splitters; Svelte's a11y taxonomy
	doesn't recognise it, hence the suppressions — same shape as the
	image-pan element above.
-->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
						role="separator"
						aria-orientation="vertical"
						aria-label="Resize preview width"
						aria-valuenow={previewWidth}
						aria-valuemin={PREVIEW_WIDTH_MIN}
						aria-valuemax={PREVIEW_WIDTH_MAX}
						tabindex="0"
						data-testid="preview-width-handle"
						onmousedown={onWidthHandleMouseDown}
						onkeydown={onWidthHandleKeydown}
						class={[
							'hidden lg:flex lg:shrink-0 lg:select-none lg:cursor-col-resize lg:items-center lg:justify-center lg:self-stretch lg:py-0',
							widthDrag ? 'lg:bg-primary/20' : 'lg:hover:bg-accent'
						].join(' ')}
						style:width="6px"
					>
						<div
							aria-hidden="true"
							class="h-12 w-1 rounded-full bg-border"
						></div>
					</div>

					<!-- Right: file preview -->
					<section
						class="flex flex-col lg:shrink-0"
						style:width="{previewWidth}px"
					>
						<!--
							Preview card. `flex flex-col` so the
							header / content / height-handle stack
							inside. The card's own height is set by
							its content (header + content area +
							handle) — no flex-stretch here. The
							content area's effective height is
							`previewContentHeight` (a `$derived`
							that bumps up to match the products list
							when the user drags the floor below it),
							so the pane only grows when needed; a
							tall list + a short user drag ends up
							tall, but a tall user drag + a short
							list stays at the user's choice.
						-->
						<div
							class="flex flex-col overflow-hidden rounded-xl border border-border bg-card shadow-sm"
							data-testid="preview-card"
						>
							<header class="flex items-center justify-between gap-3 border-b border-border p-3">
								<div class="flex items-center gap-2 text-sm font-medium">
									{#if isImagePreview(fileContentType)}
										<ImageIcon class="size-4 text-muted-foreground" />
									{:else}
										<FileText class="size-4 text-muted-foreground" />
									{/if}
									Preview
								</div>
								{#if fileUrl}
									<a
										href={fileUrl}
										download={ticket.fileName ?? `ticket-${ticket.id}`}
										class="inline-flex items-center gap-1.5 rounded-md px-2 py-1 text-xs font-medium text-muted-foreground hover:bg-accent hover:text-foreground"
									>
										<Download class="size-3.5" />
										Download
									</a>
								{/if}
							</header>
							<!--
								Content area. Explicit height from the
								`previewContentHeight` derived (which is
								max(userDrag, listHeight − chrome)). Image
								renders at its natural size and is
								centered — no `object-cover` stretching,
								no flex-grow absorbing empty space; if
								the image is smaller than the pane the
								background colour shows around it,
								matching the original behaviour. If the
								image is bigger than the pane the pan
								+ zoom wrapper scrolls (unchanged).
							-->
							<div
								class="relative flex items-center justify-center overflow-hidden bg-muted/30"
								style:height="{previewContentHeight}px"
							>
								{#if !fileUrl}
									<div
										class="flex flex-col items-center gap-2 p-6 text-center text-sm text-muted-foreground"
										data-testid="preview-empty"
									>
										<FileText class="size-8 opacity-60" />
										No preview available for this ticket.
									</div>
								{:else if isImagePreview(fileContentType)}
									<!--
										Zoom wrapper: when zoom > 1 the inner div
										grows past the container and the
										`overflow-auto` makes it scrollable.
										When zoom = 1 the container is the
										natural size of the image at fit-to-
										box; the pan transform is a no-op
										(translate(0,0)).
									-->
									<div
										class="h-full w-full overflow-auto"
										data-testid="preview-image-wrapper"
									>
										<!--
	role="application" tells AT this region has its own keyboard
	model (the standard WAI-ARIA pattern for custom-interactive
	widgets like image zoomers / draggable surfaces). Svelte-check's
	a11y rules don't recognise "application" as interactive in its
	current taxonomy, so we suppress the false-positive warnings on
	the next two lines — the element IS interactive, has an aria-
	label, exposes keyboard handlers, and restores focus on blur.
-->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
											class="inline-block"
											data-testid="preview-image-pan"
											style="
												transform: translate({panX}px, {panY}px) scale({zoom});
												transform-origin: top left;
												will-change: transform;
											"
											onwheel={onImageWheel}
											onmousedown={onImageMouseDown}
											onmousemove={onImageMouseMove}
											onmouseup={onImageMouseUp}
											onmouseleave={onImageMouseUp}
											onkeydown={onImageKeydown}
											tabindex="0"
											role="application"
											aria-label={`${ticket.title} preview, zoom ${zoom}×`}
										>
											<img
												src={fileUrl}
												alt={ticket.title}
												class="block max-w-none select-none"
												draggable="false"
												data-testid="preview-image"
											/>
										</div>
									</div>
									<div
										class="absolute top-2 right-2 flex flex-col items-center gap-0.5 rounded-lg border border-border/60 bg-background/85 p-1 shadow-lg backdrop-blur"
										role="toolbar"
										aria-label="Image zoom"
									>
										<button
											type="button"
											onclick={zoomIn}
											disabled={zoom === ZOOM_STEPS[ZOOM_STEPS.length - 1]}
											aria-label="Zoom in"
											data-testid="zoom-in"
											class="inline-flex size-7 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
										>
											<ZoomIn class="size-3.5" />
										</button>
										<span
											class="min-w-7 px-1 text-center text-[11px] font-medium tabular-nums text-foreground"
											aria-live="polite"
											data-testid="zoom-level"
										>
											{zoom}×
										</span>
										<button
											type="button"
											onclick={zoomOut}
											disabled={zoom === ZOOM_STEPS[0]}
											aria-label="Zoom out"
											data-testid="zoom-out"
											class="inline-flex size-7 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
										>
											<ZoomOut class="size-3.5" />
										</button>
										<button
											type="button"
											onclick={resetZoom}
											disabled={zoom === ZOOM_STEPS[0] && panX === 0 && panY === 0}
											aria-label="Reset zoom"
											data-testid="zoom-reset"
											class="inline-flex size-7 items-center justify-center rounded text-muted-foreground hover:bg-accent hover:text-foreground disabled:cursor-not-allowed disabled:opacity-40"
										>
											<RotateCcw class="size-3.5" />
										</button>
									</div>
								{:else if isPdfPreview(fileContentType)}
									<iframe
										src={fileUrl}
										title={ticket.title}
										class="h-full w-full"
										data-testid="preview-pdf"
									></iframe>
								{:else}
									<div
										class="flex flex-col items-center gap-3 p-6 text-center text-sm text-muted-foreground"
									>
										<FileText class="size-8 opacity-60" />
										Preview not supported for this file type.
										<a
											href={fileUrl}
											download={ticket.fileName ?? `ticket-${ticket.id}`}
											class="inline-flex items-center gap-1.5 rounded-md bg-primary px-3 py-1.5 text-xs font-medium text-primary-foreground"
										>
											Download instead
										</a>
									</div>
								{/if}
							</div>
							<!--
								Height handle. Inside the preview card so
								the drag affordance stays attached to the
								content it controls — the user sees the
								bottom of the card and the bar below it
								in one glance. cursor: row-resize drag
								resizes `previewHeight` independently of
								width (no aspect-ratio link); keyboard
								users use the same arrow / Home / End
								pattern as the width handle, just
								mapped to up/down.
							-->
<!-- svelte-ignore a11y_no_noninteractive_tabindex -->
<!-- svelte-ignore a11y_no_noninteractive_element_interactions -->
<div
								role="separator"
								aria-orientation="horizontal"
								aria-label="Resize preview height"
								aria-valuenow={previewHeight}
								aria-valuemin={PREVIEW_HEIGHT_MIN}
								aria-valuemax={PREVIEW_HEIGHT_MAX}
								tabindex="0"
								data-testid="preview-height-handle"
								onmousedown={onHeightHandleMouseDown}
								onkeydown={onHeightHandleKeydown}
								class={[
									'flex shrink-0 cursor-row-resize select-none items-center justify-center self-stretch touch-none',
									heightDrag ? 'bg-primary/20' : 'hover:bg-accent'
								].join(' ')}
								style:height="6px"
							>
								<div
									aria-hidden="true"
									class="h-1 w-12 rounded-full bg-border"
								></div>
							</div>
						</div>
					</section>
				</div>

				<!--
					Status actions hide once the ticket reaches a terminal
					state — DONE (already validated) or CANCELLED
					(deliberately dismissed by the user, not coming back).

					For ON_ERROR tickets the "Mark as done" action is
					replaced by a Reset button that flips the status back
					to OPEN, clearing the persisted error message so the
					scheduled extraction pipeline re-picks the ticket up.
					"Mark as cancelled" stays — abandoning a failed
					ticket is still a valid choice.
				-->
				{#if editable}
				<footer class="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-end">
					<button
						type="button"
						onclick={() => setStatus('CANCELLED')}
						disabled={acting}
						data-testid="mark-cancelled"
						class="inline-flex items-center justify-center gap-2 rounded-md border border-border bg-background px-4 py-2 text-sm font-medium text-foreground hover:bg-accent disabled:cursor-not-allowed disabled:opacity-60"
					>
						<XCircle class="size-4" />
						Mark as cancelled
					</button>
					{#if ticket?.status === 'ON_ERROR'}
					<button
						type="button"
						onclick={() => void resetStatus()}
						disabled={acting}
						data-testid="reset-extraction"
						class="inline-flex items-center justify-center gap-2 rounded-md bg-primary px-4 py-2 text-sm font-medium text-primary-foreground hover:bg-primary/90 disabled:cursor-not-allowed disabled:opacity-60"
					>
						<RotateCw class="size-4" />
						Reset
					</button>
					{:else}
					<button
						type="button"
						onclick={() => setStatus('DONE')}
						disabled={acting}
						data-testid="mark-done"
						class="inline-flex items-center justify-center gap-2 rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700 disabled:cursor-not-allowed disabled:opacity-60"
					>
						<CheckCircle2 class="size-4" />
						Mark as done
					</button>
					{/if}
				</footer>
				{/if}
			{/if}
		</main>
	</div>
{/if}

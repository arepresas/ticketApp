<svelte:options customElement="ticket-detail-app" />

<script lang="ts">
	/**
	 * TicketDetailApp — custom element shell for the per-ticket detail
	 * screen.
	 *
	 * Why a custom element (not a plain Svelte component)?
	 * The host page (index.html) mounts this as `<ticket-detail-app>`. Shadow
	 * DOM isolates Tailwind utilities inside this subtree — we inject
	 * the compiled stylesheet via {@html} so utility classes reach the
	 * DOM. Same pattern as the other app shells.
	 *
	 * Visibility:
	 * Driven by the `is-detail` body class toggled from the
	 * PendingTicketsApp card click (see index.html CSS gate). The
	 * route is encoded in the URL hash as `#ticket/<id>` so a deep
	 * link to a specific ticket works once the user is signed in.
	 * This element always mounts once but is `display: none` until
	 * the class flips.
	 *
	 * Data flow:
	 *  - The fetch fires on either:
	 *      a) the `detail:open` CustomEvent dispatched from
	 *         PendingTicketsApp when a card is clicked, or
	 *      b) the `$effect` re-running when `auth.isAuthenticated`
	 *         flips to true while the body class is already set (deep
	 *         link from an email, signed in cold).
	 *  - Both routes land in `load(id)` which fetches the ticket,
	 *    its extraction, and its file in parallel. The blob URL is
	 *    revoked on the next load (or unmount) so we don't leak
	 *    memory.
	 *  - Errors: a missing extraction renders an "Awaiting AI" empty
	 *    state (the scheduler hasn't picked it up yet, or it failed
	 *    and is sitting in ON_ERROR). Missing file renders a "No
	 *    preview" placeholder.
	 *
	 * Actions:
	 *  - "Mark as DONE" — PATCH the status, fire `ticket:updated` so
	 *    the pending list reloads, then close the screen.
	 *  - "Mark as CANCELLED" — same shape. Both buttons are
	 *    optimistic-free: if the PATCH fails, we surface the error
	 *    inline and leave the screen open so the user can retry.
	 */
	import tailwindCss from '../../app.css?inline';

	import {
		X,
		Download,
		FileText,
		Image as ImageIcon,
		AlertCircle,
		Loader2,
		CheckCircle2,
		XCircle,
		ZoomIn,
		ZoomOut,
		RotateCcw
	} from '@lucide/svelte';

	import { auth } from '../auth/store.svelte';
	import {
		getTicket,
		getTicketExtraction,
		getTicketFile,
		updateTicketStatus,
		TicketApiError,
		type CreatedTicket,
		type TicketExtraction
	} from '../api/tickets';

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
		const m = /^#ticket\/([0-9a-fA-F-]{36})$/.exec(h);
		return m ? m[1] : null;
	}

	let ticketId = $state<string | null>(parseTicketIdFromHash());
	let ticket = $state<CreatedTicket | null>(null);
	let extraction = $state<TicketExtraction | null>(null);
	let fileUrl = $state<string | null>(null);
	let fileContentType = $state<string | null>(null);
	let loading = $state(false);
	let errorMessage = $state<string | null>(null);
	let acting = $state(false);

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
			return new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' }).format(
				new Date(iso)
			);
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

	const isImagePreview = (ct: string | null): boolean =>
		!!ct && ct.startsWith('image/');

	const isPdfPreview = (ct: string | null): boolean =>
		ct === 'application/pdf';

	async function load(id: string): Promise<void> {
		const token = readSessionToken();
		if (!token) {
			errorMessage = 'Session expired. Sign in again to view this ticket.';
			ticket = null;
			extraction = null;
			return;
		}
		// Revoke the previous blob URL so we don't leak memory across
		// reloads. Safe to call with null (revokeObjectURL ignores).
		if (fileUrl) {
			URL.revokeObjectURL(fileUrl);
			fileUrl = null;
		}
		loading = true;
		errorMessage = null;
		try {
			// Fan-out fetch — ticket + extraction + file in parallel.
			// The file is best-effort: tickets created before the
			// upload feature landed (or uploaded with a missing
			// content type) have no bytes to preview.
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
		} catch (err: unknown) {
			ticket = null;
			extraction = null;
			if (err instanceof TicketApiError) {
				if (err.status === 404) {
					errorMessage =
						'This ticket is no longer available — it may have been deleted.';
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

	function close(): void {
		document.body.classList.remove('is-detail');
		if (fileUrl) {
			URL.revokeObjectURL(fileUrl);
			fileUrl = null;
		}
		history.replaceState(null, '', window.location.pathname + window.location.search);
	}

	async function setStatus(status: 'DONE' | 'CANCELLED'): Promise<void> {
		if (!ticketId || acting) return;
		const token = readSessionToken();
		if (!token) return;
		acting = true;
		errorMessage = null;
		try {
			await updateTicketStatus(token, ticketId, status);
			// Refresh the pending list (if it's mounted). The shell
			// listens for this and re-fetches.
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
	 * Two triggers can open this screen:
	 *  1. PendingTicketsApp card click — fires `detail:open` with the
	 *     ticket id in {@code CustomEvent.detail}.
	 *  2. Auth flip while the body class is already set + hash is
	 *     {@code #ticket/<id>} (deep link from an email, signed in
	 *     cold).
	 */
	$effect(() => {
		// Re-sync the id from the hash on every effect tick. Cheap
		// (regex parse) and makes the back button usable — going back
		// to #ticket/A then forward to #ticket/B re-fetches the right
		// one without a full reload.
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
			// Path 2 only — first load is triggered by the event
			// listener below. We guard so we don't double-fetch on
			// the first open.
			if (!ticket || ticket.id !== ticketId) {
				void load(ticketId);
			}
		}
	});

	$effect(() => {
		const handler = (e: Event): void => {
			const detail = (e as CustomEvent<{ id: string }>).detail;
			if (detail?.id) {
				void load(detail.id);
			}
		};
		window.addEventListener('detail:open', handler);
		return () => {
			window.removeEventListener('detail:open', handler);
			// Revoke the blob URL on unmount.
			if (fileUrl) URL.revokeObjectURL(fileUrl);
		};
	});
</script>

<svelte:element this={'style'}>{@html tailwindCss}</svelte:element>

{#if auth.isAuthenticated}
	<div
		class="min-h-screen bg-background text-foreground font-sans antialiased"
		data-testid="ticket-detail-screen"
	>
		<main class="mx-auto flex w-full max-w-6xl flex-col gap-6 px-4 py-8 sm:px-6 lg:px-8">
			<header class="flex items-start justify-between gap-4">
				<div class="min-w-0 flex-1">
					<h1 class="truncate text-2xl font-bold tracking-tight sm:text-3xl">
						{ticket?.title ?? 'Ticket'}
					</h1>
					{#if ticket}
						<p class="mt-1 truncate text-sm text-muted-foreground">
							Created {fmtDateTime(ticket.createdAt)}
							{#if ticket.description}· {ticket.description}{/if}
						</p>
					{/if}
				</div>
				<button
					type="button"
					onclick={close}
					aria-label="Close"
					data-testid="detail-close"
					class="inline-flex size-9 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
				>
					<X class="size-4" />
				</button>
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
				<div class="grid gap-6 lg:grid-cols-[1fr_1fr]">
					<!-- Left: structured extraction -->
					<section class="flex flex-col gap-4">
						<div
							class="rounded-xl border border-border bg-card p-5 shadow-sm"
							data-testid="extraction-card"
						>
							<header class="mb-4 flex items-start justify-between gap-3">
								<div>
									<h2 class="text-lg font-semibold">
										{extraction?.merchant ?? 'Awaiting AI extraction'}
									</h2>
									{#if extraction}
										<p class="mt-0.5 text-xs text-muted-foreground">
											{extraction.category ?? 'uncategorised'} · {fmtDate(extraction.purchaseDate)}
											· extracted by {extraction.model} on {fmtDateTime(extraction.extractedAt)}
										</p>
									{:else}
										<p class="mt-0.5 text-xs text-muted-foreground">
											The AI hasn't run on this ticket yet. Check back in a moment.
										</p>
									{/if}
								</div>
								{#if extraction}
									<div class="text-right">
										<div class="text-xs uppercase tracking-wide text-muted-foreground">
											Total
										</div>
										<div class="text-xl font-bold tabular-nums" data-testid="extraction-total">
											{fmtAmount(extraction.totalAmount, extraction.currency)}
										</div>
									</div>
								{/if}
							</header>

							{#if extraction && extraction.products.length > 0}
								<div class="overflow-x-auto">
									<table class="w-full text-sm">
										<thead class="text-left text-xs uppercase tracking-wide text-muted-foreground">
											<tr>
												<th class="py-2 pr-3 font-medium">Item</th>
												<th class="py-2 pr-3 text-right font-medium">Qty</th>
												<th class="py-2 pr-3 font-medium">Unit</th>
												<th class="py-2 pr-3 text-right font-medium">€/unit</th>
												<th class="py-2 text-right font-medium">Line</th>
											</tr>
										</thead>
										<tbody class="divide-y divide-border">
											{#each extraction.products as line, i (i)}
												<tr data-testid="product-line">
													<td class="py-2 pr-3 font-medium">{line.name}</td>
													<td class="py-2 pr-3 text-right tabular-nums">
														{line.quantity}
													</td>
													<td class="py-2 pr-3 text-muted-foreground">
														{line.unit ?? '—'}
													</td>
													<td class="py-2 pr-3 text-right tabular-nums">
														{line.pricePerUnit.toFixed(2)}
													</td>
													<td class="py-2 text-right tabular-nums">
														{line.lineTotal.toFixed(2)}
													</td>
												</tr>
											{/each}
										</tbody>
									</table>
								</div>
							{:else if !extraction}
								<div
									class="rounded-md border border-dashed border-border bg-muted/30 p-8 text-center text-sm text-muted-foreground"
									data-testid="extraction-empty"
								>
									<FileText class="mx-auto mb-2 size-6 opacity-60" />
									Waiting for the AI to extract the structured data.
								</div>
							{:else}
								<div
									class="rounded-md border border-dashed border-border bg-muted/30 p-6 text-center text-sm text-muted-foreground"
								>
									This receipt has no product lines.
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

					<!-- Right: file preview -->
					<section class="flex flex-col gap-4">
						<div
							class="overflow-hidden rounded-xl border border-border bg-card shadow-sm"
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
							<div
								class="relative flex aspect-[3/4] items-center justify-center bg-muted/30 sm:aspect-[4/3]"
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
									<!--
										Floating zoom controls (glass effect
										so they stay readable over any image
										color). aria-live="polite" announces
										the new zoom level to screen readers.
									-->
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
						</div>
					</section>
				</div>

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
				</footer>
			{/if}
		</main>
	</div>
{/if}
<svelte:options customElement="tickets-table" />

<script lang="ts">
	/**
	 * All-tickets table for the authenticated user's dashboard.
	 *
	 * Self-fetching: the component reads the session token from
	 * sessionStorage, calls `listAllTickets(token)` on mount, and
	 * re-fetches when an `auth:expired` event fires (token cleared
	 * remotely → table goes back to the loading state until the
	 * user re-authenticates and the dashboard remounts). Also
	 * re-fetches on `ticket:updated` so the row the user just
	 * edited / status-changed shows up to date without a manual
	 * refresh when they return from the detail view.
	 *
	 * Why self-fetching instead of receiving `tickets` as a prop:
	 *   * the parent (Dashboard.svelte) still needs the chart data
	 *     via its own `fetchDashboard()` mock — coupling the table
	 *     to that mock would require either mock gymnastics in the
	 *     test or threading a second fetch through the parent.
	 *   * the table's data shape ({@code CreatedTicket}[]) is much
	 *     narrower than the dashboard's `Dashboard` payload, so
	 *     the two endpoints can evolve independently.
	 *
	 * Status badges cover every {@link TicketStatus} value (matches
	 * the pending list's colour scheme).
	 */
	import { onMount } from 'svelte';
	import { RefreshCcw, RotateCw, FileText, Image as ImageIcon } from '@lucide/svelte';

	import {
		listAllTickets,
		retryTicket,
		TicketApiError,
		type CreatedTicket,
		type TicketStatus
	} from '../../api/tickets';
	import { navigate } from '../../navigation';

	type Props = {
		/**
		 * Optional hook the parent uses to wire an external refresh trigger
		 * (e.g. the dashboard greeting header) to this table's `load()`. Called
		 * once on mount with the same async fn the local Refresh button calls.
		 * Cheap to skip — leaves the table behaving exactly as before.
		 */
		registerLoad?: (loadFn: () => Promise<void>) => void;
	};

	let { registerLoad }: Props = $props();

	const SESSION_STORAGE_KEY = 'ticketapp.session';

	function readSessionToken(): string | null {
		const s = (globalThis as { window?: { sessionStorage?: Storage } }).window
			?.sessionStorage;
		if (!s) return null;
		try {
			return s.getItem(SESSION_STORAGE_KEY);
		} catch {
			return null;
		}
	}

	let tickets = $state<CreatedTicket[]>([]);
	let loading = $state(true);
	let errorMessage = $state<string | null>(null);

	// Per-ticket id currently being retried. Drives the disabled state
	// on the row-level Retry button so a double-click doesn't fire two
	// PATCHes. Cleared in the finally block of `handleRetry`.
	let retryingId = $state<string | null>(null);
	// Last retry error surfaced at the table level (not the per-ticket
	// fetch error). Distinct from `errorMessage` so the banner that
	// shows "load failed" and "retry failed" don't fight for the same
	// slot.
	let retryError = $state<string | null>(null);

	// Status → readable label. Mirrors PendingTicketsApp so the user
	// sees the same word on both screens.
	const STATUS_LABEL: Record<TicketStatus, string> = {
		OPEN: 'Open',
		IN_PROGRESS: 'In progress',
		ON_ERROR: 'Error',
		DONE: 'Done',
		CANCELLED: 'Cancelled'
	};

	// Status → pill colours. Open/In progress keep their "needs
	// attention" amber/sky; Error stands out in red so the operator
	// notices it; terminal states (Done, Cancelled) get emerald/zinc
	// so the table scannability matches the rest of the dashboard.
	const statusBadgeClass: Record<TicketStatus, string> = {
		OPEN: 'bg-blue-500/10 text-blue-700 ring-1 ring-inset ring-blue-500/20 dark:text-blue-300',
		IN_PROGRESS:
			'bg-amber-500/10 text-amber-700 ring-1 ring-inset ring-amber-500/20 dark:text-amber-300',
		ON_ERROR:
			'bg-red-500/10 text-red-700 ring-1 ring-inset ring-red-500/20 dark:text-red-300',
		DONE: 'bg-emerald-500/10 text-emerald-700 ring-1 ring-inset ring-emerald-500/20 dark:text-emerald-300',
		CANCELLED:
			'bg-zinc-500/10 text-zinc-700 ring-1 ring-inset ring-zinc-500/20 dark:text-zinc-300'
	};

	const dateFmt = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium' });
	const dtFmt = new Intl.DateTimeFormat(undefined, {
		dateStyle: 'medium',
		timeStyle: 'short'
	});

	function formatSize(bytes: number | null): string {
		if (bytes == null || bytes <= 0) return '—';
		const units = ['B', 'KB', 'MB', 'GB'];
		let value = bytes;
		let unit = 0;
		while (value >= 1024 && unit < units.length - 1) {
			value /= 1024;
			unit++;
		}
		const rounded = value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1);
		return `${rounded} ${units[unit]}`;
	}

	const isImage = (contentType: string | null): boolean =>
		!!contentType && contentType.startsWith('image/');

	async function load(): Promise<void> {
		const token = readSessionToken();
		if (!token) {
			// No session = nothing to render. The dashboard's auth
			// gate should already have hidden this component, but
			// staying defensive keeps the contract tight.
			tickets = [];
			loading = false;
			errorMessage = null;
			return;
		}
		loading = true;
		errorMessage = null;
		try {
			tickets = await listAllTickets(token);
		} catch (err: unknown) {
			tickets = [];
			if (err instanceof TicketApiError) {
				errorMessage = `Failed to load tickets (${err.status}): ${err.message}`;
			} else {
				errorMessage = `Failed to load tickets: ${err instanceof Error ? err.message : 'unknown error'}`;
			}
		} finally {
			loading = false;
		}
	}

	function openDetail(id: string): void {
		navigate({ kind: 'detail', ticketId: id });
	}

	// Per-ticket retry: PATCHes status back to OPEN (which clears the
	// error message via Ticket.withStatus on the BFF) and re-enqueues
	// the ticket for the scheduled extraction pipeline. `retryTicket`
	// is a thin wrapper over `updateTicketStatus(id, 'OPEN')` — kept
	// separate so the call site reads as intent. The refetch after
	// the PATCH lands picks up the OPEN status immediately; the next
	// cron tick will then flip it to IN_PROGRESS / DONE / ON_ERROR.
	async function handleRetry(id: string, ev: MouseEvent): Promise<void> {
		// The row's onclick navigates to the detail view; stop the
		// event from bubbling so clicking Retry doesn't also fire a
		// detail navigation. The dedicated button is the only path
		// that should run on a Retry click.
		ev.stopPropagation();
		const token = readSessionToken();
		if (!token) return;
		retryingId = id;
		retryError = null;
		try {
			await retryTicket(token, id);
			await load();
		} catch (err: unknown) {
			retryError =
				err instanceof TicketApiError
					? `Retry failed (${err.status}): ${err.message}`
					: `Retry failed: ${err instanceof Error ? err.message : 'unknown error'}`;
		} finally {
			retryingId = null;
		}
	}

	// Truncate the error message to a single line in the table cell.
	// The full text is preserved in the title attribute (tooltip on
	// hover) and in the detail view. 60 chars is wide enough for the
	// common "status=503 MiniMax returned 500: ..." shape without
	// forcing the cell to expand.
	function truncateError(msg: string | null, max = 60): string {
		if (!msg) return '';
		if (msg.length <= max) return msg;
		return msg.slice(0, max - 1) + '…';
	}

	onMount(() => {
		void load();
		// Expose `load` to the parent so an external trigger (e.g. the
		// dashboard header) can refetch the tickets list without us
		// lifting state up. Single registration on mount is enough —
		// the fn reference is stable for the component's lifetime.
		registerLoad?.(load);
		const onAuthExpired = (): void => {
			// Token cleared while the dashboard is mounted. Drop the
			// local copy and skip showing an error — the parent
			// will remount after re-authentication.
			tickets = [];
			errorMessage = null;
		};
		// The detail screen dispatches `ticket:updated` after a
		// successful status change / metadata edit. We're still
		// mounted as a sibling, so refresh the table so the new
		// status / row appears without the user clicking refresh.
		// Same hook as PendingTicketsApp.
		const onTicketUpdated = (): void => {
			void load();
		};
		// Belt-and-braces: `ticket:updated` fires while the
		// dashboard is still hidden (the detail screen closes via
		// history.back right after). The fetch promise resolves
		// before the dashboard is visible, and Svelte's reactive
		// update inside a hidden subtree can be skipped on the
		// first paint after the body class flips. Re-running
		// `load()` when the document transitions back to visible
		// guarantees a fresh paint. Cheap — the request is
		// idempotent and the server returns the same payload.
		const onVisibilityChange = (): void => {
			if (document.visibilityState === 'visible') {
				void load();
			}
		};
		// Re-clicking the active Dashboard nav link (in the global
		// header) calls `navigate({kind:'dashboard'})` while we're
		// already on the dashboard route. The router dispatches a
		// `dashboard:refresh` event in that case — same-route re-
		// click — so the user-visible behaviour matches "the link
		// reloads the screen". Skip when the initial mount fetch is
		// still in flight to avoid double-fetching on first paint.
		const onDashboardRefresh = (): void => {
			if (loading) return;
			void load();
		};
		window.addEventListener('auth:expired', onAuthExpired);
		window.addEventListener('ticket:updated', onTicketUpdated);
		document.addEventListener('visibilitychange', onVisibilityChange);
		window.addEventListener('dashboard:refresh', onDashboardRefresh);
		return () => {
			window.removeEventListener('auth:expired', onAuthExpired);
			window.removeEventListener('ticket:updated', onTicketUpdated);
			document.removeEventListener('visibilitychange', onVisibilityChange);
			window.removeEventListener('dashboard:refresh', onDashboardRefresh);
		};
	});
</script>

<div
	class="overflow-x-auto rounded-lg border border-border"
	data-testid="tickets-table"
>
	<header class="flex items-center justify-between gap-3 border-b border-border bg-muted/50 px-4 py-3">
		<!--
			Title doubles as a refresh trigger: clicking it re-runs
			load(). It's a real <button> nested inside the <h2> so
			keyboard activation (Enter / Space) and focus work natively
			without manual role/tabindex plumbing — and svelte-check
			doesn't flag it as "non-interactive element with interactive
			role". Visual styling lives on the button (border-0 + bg-
			transparent + p-0 + text-inherit) so it still reads as a
			plain heading. The dedicated Refresh button on the right is
			kept for clarity — both call the same `load()`.
		-->
		<h2 class="m-0 text-sm font-semibold tracking-tight">
			<button
				type="button"
				class="cursor-pointer select-none border-0 bg-transparent p-0 text-inherit hover:text-foreground/80 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background rounded"
				data-testid="tickets-title"
				onclick={() => void load()}
			>
				All tickets
			</button>
		</h2>
		<button
			type="button"
			onclick={load}
			disabled={loading}
			aria-label="Refresh"
			data-testid="tickets-refresh"
			class="inline-flex h-8 items-center gap-1.5 rounded-md border border-input bg-background px-2.5 text-xs font-medium hover:bg-accent disabled:opacity-50"
		>
			<RefreshCcw class="size-3.5" />
			Refresh
		</button>
	</header>

	{#if errorMessage}
		<div
			role="alert"
			data-testid="tickets-error"
			class="rounded-b-lg border-t border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
		>
			{errorMessage}
		</div>
	{/if}

	{#if retryError}
		<!--
			Surface retry failures separately from the table-load
			banner. Same destructive styling so the user notices, but
			scoped to the action they just attempted (vs. a generic
			"couldn't load" message).
		-->
		<div
			role="alert"
			data-testid="tickets-retry-error"
			class="rounded-b-lg border-t border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
		>
			{retryError}
		</div>
	{/if}

	{#if loading && tickets.length === 0}
		<!-- Loading: rows skeleton so layout doesn't jump when data lands. -->
		<div class="space-y-2 p-4" aria-busy="true">
			{#each Array(4) as _, i (i)}
				<div class="h-12 animate-pulse rounded bg-muted/40"></div>
			{/each}
			<p class="sr-only">Loading tickets…</p>
		</div>
	{:else if tickets.length === 0}
		<div
			class="px-4 py-8 text-center text-sm text-muted-foreground"
			data-testid="tickets-empty"
		>
			No tickets yet — upload a receipt to get started.
		</div>
	{:else}
		<table class="w-full text-sm">
			<thead
				class="border-b border-border bg-muted/30 text-xs uppercase tracking-wider text-muted-foreground"
			>
				<tr>
					<th scope="col" class="px-4 py-3 text-left font-medium">Ticket</th>
					<th scope="col" class="px-4 py-3 text-left font-medium">File</th>
					<th scope="col" class="px-4 py-3 text-right font-medium">Size</th>
					<th scope="col" class="px-4 py-3 text-left font-medium">Created</th>
					<th scope="col" class="px-4 py-3 text-left font-medium">Status</th>
					<th scope="col" class="px-4 py-3 text-right font-medium">Attempts</th>
					<th scope="col" class="px-4 py-3 text-left font-medium">Error</th>
					<th scope="col" class="px-4 py-3 text-right font-medium">Actions</th>
				</tr>
			</thead>
			<tbody class="divide-y divide-border">
				{#each tickets as t (t.id)}
					<tr
						class="cursor-pointer transition-colors hover:bg-muted/40"
						data-testid="ticket-row"
						onclick={() => openDetail(t.id)}
					>
						<td class="px-4 py-3">
							<p class="truncate font-medium text-foreground">{t.title}</p>
							{#if t.description}
								<p class="mt-0.5 truncate text-xs text-muted-foreground">
									{t.description}
								</p>
							{/if}
						</td>
						<td class="px-4 py-3">
							<div class="flex items-center gap-2 text-xs text-muted-foreground">
								{#if isImage(t.contentType)}
									<ImageIcon class="size-3.5 shrink-0" aria-hidden="true" />
								{:else}
									<FileText class="size-3.5 shrink-0" aria-hidden="true" />
								{/if}
								<span class="truncate">{t.fileName ?? 'no file'}</span>
							</div>
						</td>
						<td
							class="px-4 py-3 text-right font-mono tabular-nums text-xs text-muted-foreground"
						>
							{formatSize(t.sizeBytes)}
						</td>
						<td
							class="px-4 py-3 whitespace-nowrap text-xs text-muted-foreground"
						>
							<span title={dtFmt.format(new Date(t.createdAt))}>
								{dateFmt.format(new Date(t.createdAt))}
							</span>
						</td>
						<td class="px-4 py-3">
							<span
								class={[
									'inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium',
									statusBadgeClass[t.status]
								].join(' ')}
								data-testid="status-badge"
							>
								{STATUS_LABEL[t.status]}
							</span>
						</td>
						<td
							class="px-4 py-3 text-right font-mono tabular-nums text-xs text-muted-foreground"
							data-testid="ticket-attempts"
						>
							{t.attempts}
						</td>
						<td
							class="px-4 py-3 text-xs text-muted-foreground max-w-[16rem]"
							data-testid="ticket-error"
						>
							{#if t.errorMessage}
								<span
									class="block truncate"
									title={t.errorMessage}
									data-testid="ticket-error-text"
								>
									{truncateError(t.errorMessage)}
								</span>
							{:else}
								<span class="text-muted-foreground/50">—</span>
							{/if}
						</td>
						<td class="px-4 py-3 text-right">
							{#if t.status === 'ON_ERROR'}
								<button
									type="button"
									onclick={(ev) => void handleRetry(t.id, ev)}
									disabled={retryingId === t.id}
									aria-label="Retry extraction"
									data-testid="ticket-retry"
									class="inline-flex h-7 items-center gap-1 rounded-md border border-input bg-background px-2 text-xs font-medium hover:bg-accent disabled:opacity-50"
								>
									<RotateCw class="size-3" />
									Retry
								</button>
							{/if}
						</td>
					</tr>
				{/each}
			</tbody>
		</table>
	{/if}
</div>

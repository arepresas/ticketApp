<svelte:options customElement="pending-tickets-app" />

<script lang="ts">
	/**
	 * PendingTicketsApp — custom element shell for the "Pending tickets" list.
	 *
	 * Same pattern as `NewTicketApp.svelte` and `DashboardApp.svelte`:
	 *  - Mounts as `<pending-tickets-app>` from index.html. Tailwind
	 *    utilities are injected into the shadow root via `{@html}` so
	 *    the design tokens (`bg-background`, `text-foreground`, …) keep
	 *    working without duplicating the stylesheet.
	 *  - Driven by the `is-pending` body class toggled from the Header
	 *    "Pending tickets" link — the index.html CSS gate keeps the
	 *    element hidden when the user is on the landing page or the
	 *    dashboard.
	 *  - Auth-gated render: refuses to render the body when signed-out,
	 *    matching the upload screen's behaviour. The BFF rejects
	 *    unauthenticated requests at the SessionFilter layer, so the
	 *    fetch is gated client-side as defense in depth.
	 *
	 * Data flow:
	 *  - The fetch fires on either:
	 *      a) the Header `pending:open` CustomEvent (user clicks the
	 *         "Pending tickets" link while signed-in), or
	 *      b) the `$effect` re-running when `auth.isAuthenticated`
	 *         flips to true while the body class is already set (deep
	 *         link to `#pending` from an email, signed in cold).
	 *    `loadTickets` itself is idempotent — both paths route through
	 *    it. The session token comes from sessionStorage so we never
	 *    share state across components.
	 *  - Errors surface as an inline alert with the `TicketApiError`
	 *    message. The list itself renders an explicit empty state when
	 *    the API returns an empty array — no silent fallthrough.
	 *  - The manual refresh button re-runs the fetch; useful when the
	 *    user changes a ticket's status from a future detail view and
	 *    wants to see it disappear from the pending list.
	 */
	import tailwindCss from '../../app.css?inline';

	import { onMount } from 'svelte';
	import { RefreshCcw, X, FileText, Image as ImageIcon } from '@lucide/svelte';

	import { auth } from '../auth/store.svelte';
	import {
		listPendingTickets,
		TicketApiError,
		type CreatedTicket,
		type TicketStatus
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

	let tickets = $state<CreatedTicket[]>([]);
	let loading = $state(false);
	let errorMessage = $state<string | null>(null);

	const totalBytes = $derived(
		tickets.reduce((acc, t) => acc + (t.sizeBytes ?? 0), 0)
	);

	const STATUS_LABEL: Record<TicketStatus, string> = {
		OPEN: 'Open',
		IN_PROGRESS: 'In progress',
		DONE: 'Done',
		CANCELLED: 'Cancelled'
	};

	function statusBadgeClass(status: TicketStatus): string {
		switch (status) {
			case 'OPEN':
				return 'bg-blue-500/10 text-blue-700 dark:text-blue-300 ring-blue-500/20';
			case 'IN_PROGRESS':
				return 'bg-amber-500/10 text-amber-700 dark:text-amber-300 ring-amber-500/20';
			case 'DONE':
				return 'bg-emerald-500/10 text-emerald-700 dark:text-emerald-300 ring-emerald-500/20';
			case 'CANCELLED':
				return 'bg-zinc-500/10 text-zinc-700 dark:text-zinc-300 ring-zinc-500/20';
		}
	}

	function humanSize(bytes: number | null): string {
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

	function formatDate(iso: string): string {
		try {
			return new Intl.DateTimeFormat(undefined, {
				dateStyle: 'medium',
				timeStyle: 'short'
			}).format(new Date(iso));
		} catch {
			return iso;
		}
	}

	function isImage(contentType: string | null): boolean {
		return !!contentType && contentType.startsWith('image/');
	}

	async function loadTickets(): Promise<void> {
		const token = readSessionToken();
		if (!token) {
			// Mirror the upload screen's failure mode: refuse to talk to
			// the BFF without a session, instead of bubbling a 401.
			errorMessage = 'Session expired. Sign in again to view pending tickets.';
			tickets = [];
			return;
		}
		loading = true;
		errorMessage = null;
		try {
			tickets = await listPendingTickets(token);
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

	function exit(): void {
		document.body.classList.remove('is-pending');
		window.location.hash = '';
	}

	/**
	 * Open the per-ticket detail screen for the given id. Mirrors the
	 * `openPendingTickets` / `openNewTicket` pattern: set a body class
	 * so the CSS gate in index.html reveals the element, set the
	 * hash so the URL is shareable, and fire a DOM event so the
	 * detail shell can react. The detail shell's own auth gate
	 * handles the signed-out case (refuses to render), so we don't
	 * re-check here.
	 */
	function openDetail(id: string): void {
		document.body.classList.remove('is-pending');
		document.body.classList.add('is-detail');
		window.location.hash = `ticket/${id}`;
		window.dispatchEvent(
			new CustomEvent('detail:open', { detail: { id } })
		);
	}

	/**
	 * Two triggers can open this screen and need to kick off the fetch:
	 *
	 *  1. The user is already authenticated when they click the Header
	 *     "Pending tickets" link. The link sets `body.is-pending` and
	 *     dispatches a `pending:open` DOM event — the class toggle is
	 *     invisible to Svelte's reactivity (the DOM classList is not a
	 *     reactive source), so we listen for the explicit event
	 *     instead of trying to observe the class via `$effect`.
	 *
	 *  2. The user signs in while `is-pending` is already set (e.g. a
	 *     deep link to `#pending` from an email). Here `auth.isAuthenticated`
	 *     flips after the component mounts — that's a real reactive
	 *     signal and an `$effect` is the right tool.
	 *
	 * Both paths route through `loadTickets`, which is idempotent (the
	 * fetch happens against the BFF; calling it twice in quick
	 * succession is harmless — the manual refresh button does the same).
	 * The listener is registered in `onMount` and torn down on destroy
	 * so a HMR reload or component unmount doesn't leak it.
	 */
	onMount(() => {
		const openHandler = (): void => {
			void loadTickets();
		};
		window.addEventListener('pending:open', openHandler);
		return () => {
			window.removeEventListener('pending:open', openHandler);
		};
	});

	$effect(() => {
		// Path 2: auth flipped on while the screen is already visible.
		// Guard with the body class so we don't fetch for users who are
		// just signing in on the landing page.
		if (auth.isAuthenticated && document.body.classList.contains('is-pending')) {
			void loadTickets();
		}
	});

	/**
	 * When the detail screen changes a ticket's status (DONE /
	 * CANCELLED) it dispatches `ticket:updated` and closes itself.
	 * We're still mounted (the detail screen is a sibling, not a
	 * child), so we refresh our own list when the user comes back.
	 * Also refresh on first auth flip if the body class is set, to
	 * cover the case where the user signs in cold with a deep link
	 * to a ticket — the detail screen fetches that one ticket, and
	 * the user returning to the list should see it gone.
	 */
	$effect(() => {
		if (!auth.isAuthenticated) return;
		const handler = (): void => {
			void loadTickets();
		};
		window.addEventListener('ticket:updated', handler);
		return () => window.removeEventListener('ticket:updated', handler);
	});
</script>

<svelte:element this={'style'}>{@html tailwindCss}</svelte:element>

{#if auth.isAuthenticated}
	<div
		class="min-h-screen bg-background text-foreground font-sans antialiased"
		data-testid="pending-tickets-screen"
	>
		<main class="mx-auto flex w-full max-w-3xl flex-col gap-6 px-4 py-10 sm:px-6">
			<header class="flex items-start justify-between gap-4">
				<div>
					<h1 class="text-2xl font-bold tracking-tight sm:text-3xl">Pending tickets</h1>
					<p class="mt-1 text-sm text-muted-foreground">
						Every receipt that hasn't reached a terminal state — newest first.
					</p>
				</div>
				<button
					type="button"
					onclick={exit}
					aria-label="Close"
					class="inline-flex size-9 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground"
				>
					<X class="size-4" />
				</button>
			</header>

			<div class="flex items-center justify-between gap-3">
				<p class="text-xs text-muted-foreground" data-testid="summary">
					{#if loading}
						Loading…
					{:else if errorMessage}
						<span class="text-destructive">Couldn't load the list.</span>
					{:else if tickets.length === 0}
						No pending tickets.
					{:else}
						{tickets.length} pending · {humanSize(totalBytes)} total
					{/if}
				</p>
				<button
					type="button"
					onclick={() => void loadTickets()}
					disabled={loading}
					aria-label="Refresh"
					class="inline-flex h-8 items-center gap-1.5 rounded-md border border-input bg-background px-2.5 text-xs font-medium hover:bg-accent disabled:opacity-50"
				>
					<RefreshCcw class="size-3.5" aria-hidden="true" />
					Refresh
				</button>
			</div>

			{#if errorMessage}
				<p
					role="alert"
					data-testid="error"
					class="rounded-md border border-destructive/40 bg-destructive/10 px-3 py-2 text-sm text-destructive"
				>
					{errorMessage}
				</p>
			{/if}

			{#if !loading && !errorMessage && tickets.length === 0}
				<div
					class="rounded-lg border border-dashed border-border bg-muted/30 px-4 py-12 text-center"
					data-testid="empty-state"
				>
					<p class="text-sm font-medium">All caught up</p>
					<p class="mt-1 text-xs text-muted-foreground">
						New tickets will show up here until they're marked as done or cancelled.
					</p>
				</div>
			{:else if tickets.length > 0}
				<ul
					class="flex flex-col gap-2"
					data-testid="ticket-list"
				>
					{#each tickets as t (t.id)}
						<li
							class="flex items-start gap-3 rounded-lg border border-border bg-card p-3 transition-colors hover:bg-accent/40"
							data-testid="ticket-row"
						>
							<button
								type="button"
								onclick={() => openDetail(t.id)}
								class="flex flex-1 items-start gap-3 text-left"
								data-testid="ticket-row-button"
								aria-label={`Open ticket ${t.title}`}
							>
								{#if isImage(t.contentType)}
									<div
										class="flex size-10 shrink-0 items-center justify-center rounded-md bg-blue-500/10 text-blue-700 dark:text-blue-300"
										aria-hidden="true"
									>
										<ImageIcon class="size-5" />
									</div>
								{:else}
									<div
										class="flex size-10 shrink-0 items-center justify-center rounded-md bg-muted text-muted-foreground"
										aria-hidden="true"
									>
										<FileText class="size-5" />
									</div>
								{/if}
								<div class="min-w-0 flex-1">
									<p class="truncate text-sm font-medium">{t.title}</p>
									{#if t.description}
										<p class="mt-0.5 truncate text-xs text-muted-foreground">{t.description}</p>
									{/if}
									<p class="mt-1 text-[11px] text-muted-foreground">
										{t.fileName ?? 'no file'} · {humanSize(t.sizeBytes)} · {formatDate(t.createdAt)}
									</p>
								</div>
							</button>
							<span
								class={[
									'inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-medium ring-1 ring-inset',
									statusBadgeClass(t.status)
								].join(' ')}
								data-testid="status-badge"
							>
								{STATUS_LABEL[t.status]}
							</span>
						</li>
					{/each}
				</ul>
			{/if}
		</main>
	</div>
{/if}
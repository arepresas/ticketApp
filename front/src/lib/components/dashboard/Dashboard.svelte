<script lang="ts">
	/**
	 * Dashboard composition component.
	 *
	 * Owns the dashboard layout: header (greeting + user identity), 4 KPI cards,
	 * 2-up chart row, recent-tickets table. Calls `fetchDashboard()` once on
	 * mount, handles loading + error states, exposes a Retry hook on failure.
	 *
	 * Props are pure (just `user`) — the component does NOT read the auth
	 * store. That keeps it trivially testable and lets the parent (TicketApp)
	 * decide how/when to pass the user.
	 *
	 * Re-run semantics: the loader `$effect` reads `user` via `untrack` so a
	 * change to the user prop does NOT re-trigger a fetch. We only ever want
	 * one fetch per mount — auth changes that arrive after the data is in
	 * flight shouldn't restart the request or wipe the loading flag.
	 */
	import { untrack } from 'svelte';
	import { RefreshCcw } from '@lucide/svelte';

	import type { AuthUser } from '../../auth/types';
	import { fetchDashboard, type Dashboard } from '../../api/dashboard';

	import KpiCard, { type KpiIconName } from './KpiCard.svelte';
	import TicketsPerMonthChart from './TicketsPerMonthChart.svelte';
	import SpendByCategoryChart from './SpendByCategoryChart.svelte';
	import RecentTicketsTable from './RecentTicketsTable.svelte';

	type Props = { user: AuthUser };

	let { user }: Props = $props();

	// The BFF session JWT. Read from sessionStorage rather than the auth
	// store because the store deliberately exposes identity, not the
	// token — same pattern as PendingTicketsApp and TicketDetailApp.
	const SESSION_STORAGE_KEY = 'ticketapp.session';

	function readSessionToken(): string | null {
		// Both the `sessionStorage` property access and `getItem` live
		// inside the guard: a browser with storage blocked throws
		// SecurityError from the *getter*, and an exception here would
		// escape `load()`'s own try/catch and leave the page stuck on
		// its loading skeleton forever.
		try {
			const g = globalThis as { window?: { sessionStorage?: Storage } };
			return g.window?.sessionStorage?.getItem(SESSION_STORAGE_KEY) ?? null;
		} catch {
			return null;
		}
	}

	// Refresh hook for the tickets table. Set by RecentTicketsTable via its
	// `registerLoad` prop on mount. No-op until then — the table owns the
	// fetch, we just provide a clickable affordance in the greeting header.
	let refreshTickets: () => Promise<void> = async () => {};

	let data = $state<Dashboard | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

	/** Bumped per request; stale responses are discarded. */
	let loadGeneration = 0;

	// Icon-name → KpiIconName mapping. Static literals, so plain const — no
	// need to wrap in $derived.
	const KPI_ICONS: Record<'totalTickets' | 'openTickets' | 'totalSpent' | 'avgTicket', KpiIconName> =
		{
			totalTickets: 'Ticket',
			openTickets: 'TicketCheck',
			totalSpent: 'Wallet',
			avgTicket: 'TrendingUp'
		};

	// Greeting first-name extraction — split on the first space, fall back to
	// the whole trimmed name when no separator is present.
	const firstName = $derived.by((): string => {
		const trimmed = user.name.trim();
		const idx = trimmed.indexOf(' ');
		return idx === -1 ? trimmed : trimmed.slice(0, idx);
	});

	// Single loader — reused by the mount effect AND the Retry button.
	async function load(): Promise<void> {
		loading = true;
		error = null;
		const token = readSessionToken();
		if (!token) {
			// No session means no request: the endpoint answers 401, and
			// firing it anyway would trip the auth:expired handler for a
			// user who was never logged in.
			data = null;
			error = 'Not signed in.';
			loading = false;
			return;
		}
		// Monotonic request generation. A response is only committed if
		// it still belongs to the newest request: without this, a slow
		// fetch for user A could resolve after a logout and login as
		// user B and paint A's dashboard into B's screen.
		const generation = ++loadGeneration;
		try {
			const payload = await fetchDashboard(token);
			if (generation !== loadGeneration) return;
			data = payload;
		} catch (err) {
			if (generation !== loadGeneration) return;
			error = err instanceof Error ? err.message : 'Failed to load dashboard';
			data = null;
		} finally {
			if (generation === loadGeneration) loading = false;
		}
	}

	$effect(() => {
		// `untrack` reads `user` without subscribing — the effect fires once
		// on mount and never re-runs when the user prop changes. Cleaner than
		// a flag-tracked `let` because the intent is visible at the call site.
		untrack(() => {
			void user;
			void load();
		});
	});
</script>

<section class="mx-auto flex w-full max-w-screen-2xl flex-col gap-6 px-4 py-6 sm:px-6 lg:px-8">
	<!-- Header: greeting + user identity. The whole header is clickable so
	     clicking anywhere on it refreshes the tickets table below — matches
	     the title-as-button pattern in RecentTicketsTable for consistency.
	     No logout here — the existing GoogleLoginButton flow owns the auth
	     UI; duplicating it would split the source of truth. -->
	<header
		class="group -mx-2 -my-1 flex cursor-pointer items-center gap-3 rounded-lg px-2 py-1 select-none transition-colors hover:bg-muted/40 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background"
		role="button"
		tabindex="0"
		title="Click to refresh tickets"
		data-testid="dashboard-header"
		onclick={() => void refreshTickets()}
		onkeydown={(e) => {
			if (e.key === 'Enter' || e.key === ' ') {
				e.preventDefault();
				void refreshTickets();
			}
		}}
	>
		{#if user.picture}
			<img
				src={user.picture}
				alt=""
				width="32"
				height="32"
				class="size-8 rounded-full border border-border object-cover"
			/>
		{:else}
			<span
				class="flex size-8 items-center justify-center rounded-full border border-border bg-muted text-xs font-semibold uppercase text-muted-foreground"
				aria-hidden="true"
			>
				{firstName.charAt(0)}
			</span>
		{/if}
		<div class="min-w-0 flex-1">
			<h1 class="truncate text-lg font-semibold tracking-tight sm:text-xl">
				Hi, {firstName}
			</h1>
			<p class="truncate text-xs text-muted-foreground sm:text-sm">{user.email}</p>
		</div>
		<RefreshCcw
			class="ml-auto size-4 shrink-0 text-muted-foreground opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100"
			aria-hidden="true"
		/>
	</header>

	{#if error}
		<!-- Error banner — single retry hook. Visual stays muted to avoid
		     stealing focus from the dashboard when the user reloads. -->
		<div
			role="alert"
			class="flex flex-col items-start gap-3 rounded-lg border border-destructive/30 bg-destructive/5 p-4 sm:flex-row sm:items-center sm:justify-between"
		>
			<div>
				<p class="text-sm font-medium text-destructive">Failed to load dashboard</p>
				<p class="mt-0.5 text-xs text-muted-foreground">{error}</p>
			</div>
			<button
				type="button"
				onclick={load}
				class="inline-flex h-9 shrink-0 items-center justify-center rounded-md border border-destructive/40 bg-background px-4 text-sm font-medium text-destructive transition-colors hover:bg-destructive/10"
			>
				Retry
			</button>
		</div>
	{/if}

	{#if loading}
		<!-- Loading: skeleton mirrors the final grid so layout doesn't jump
		     when data arrives. animate-pulse only, no external spinner libs. -->
		<div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-busy="true">
			{#each Array(4) as _, i (i)}
				<div class="rounded-xl border border-border bg-card p-5 shadow-sm">
					<div class="h-3 w-20 animate-pulse rounded bg-muted"></div>
					<div class="mt-4 h-7 w-24 animate-pulse rounded bg-muted"></div>
					<div class="mt-2 h-3 w-16 animate-pulse rounded bg-muted"></div>
				</div>
			{/each}
		</div>
		<div class="grid grid-cols-1 gap-4 lg:grid-cols-3" aria-hidden="true">
			<div class="h-72 animate-pulse rounded-xl border border-border bg-card lg:col-span-2"></div>
			<div class="h-72 animate-pulse rounded-xl border border-border bg-card lg:col-span-1"></div>
		</div>
		<div class="h-64 animate-pulse rounded-xl border border-border bg-card" aria-hidden="true"></div>
		<p class="sr-only">Loading…</p>
	{:else if data}
		<!-- 4 KPI cards. Responsive: 1 col mobile, 2 cols tablet, 4 cols desktop.
		     The counts are formatted as counts and the money figures as
		     money — previously both went through a label-sniffing
		     heuristic, which rendered "Total tickets" as "€42.00". -->
		<div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
			<KpiCard
				label="Total tickets"
				value={data.kpis.totalTickets}
				format="count"
				icon={KPI_ICONS.totalTickets}
			/>
			<KpiCard
				label="Open tickets"
				value={data.kpis.openTickets}
				format="count"
				icon={KPI_ICONS.openTickets}
				hint="{data.kpis.openTickets} open"
			/>
			<KpiCard
				label="Total spent"
				value={data.kpis.totalSpent}
				format="money"
				currency={data.kpis.currency}
				icon={KPI_ICONS.totalSpent}
				hint="{data.kpis.extractedTicketsInCurrency} extracted"
			/>
			<KpiCard
				label="Avg ticket value"
				value={data.kpis.avgTicketValue}
				format="money"
				currency={data.kpis.currency}
				icon={KPI_ICONS.avgTicket}
			/>
		</div>

		<!-- Charts: line chart col-span-2, doughnut col-span-1. Stack on mobile. -->
		<div class="grid grid-cols-1 gap-4 lg:grid-cols-3">
			<div class="lg:col-span-2">
				<TicketsPerMonthChart data={data.ticketsPerMonth} />
			</div>
			<div class="lg:col-span-1">
				<SpendByCategoryChart data={data.spendByCategory} />
			</div>
		</div>

		<!--
			All-tickets table is self-fetching (talks to the BFF
			directly via `listAllTickets`) — it is an entity list, not an
			aggregate, so it does not belong in the dashboard payload.
			Charts and KPI cards come from `fetchDashboard()` against
			`GET /api/dashboard`. The parent header above wires into
			`refreshTickets` via `registerLoad` so a click on the
			greeting header refetches this table.
		-->
		<RecentTicketsTable
			registerLoad={(loadFn) => {
				refreshTickets = loadFn;
			}}
		/>
	{/if}
</section>
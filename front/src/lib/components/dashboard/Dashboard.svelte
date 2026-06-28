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

	import type { AuthUser } from '../../auth/types';
	import { fetchDashboard, type Dashboard } from '../../api/dashboard';

	import KpiCard, { type KpiIconName } from './KpiCard.svelte';
	import TicketsPerMonthChart from './TicketsPerMonthChart.svelte';
	import SpendByCategoryChart from './SpendByCategoryChart.svelte';
	import RecentTicketsTable from './RecentTicketsTable.svelte';

	type Props = { user: AuthUser };

	let { user }: Props = $props();

	let data = $state<Dashboard | null>(null);
	let loading = $state(true);
	let error = $state<string | null>(null);

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
		try {
			data = await fetchDashboard();
		} catch (err) {
			error = err instanceof Error ? err.message : 'Failed to load dashboard';
			data = null;
		} finally {
			loading = false;
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

<section class="mx-auto flex w-full max-w-6xl flex-col gap-6 px-4 py-6 sm:px-6 lg:px-8">
	<!-- Header: greeting + user identity. No logout here — the existing
	     GoogleLoginButton flow owns the auth UI; duplicating it would split
	     the source of truth. -->
	<header class="flex items-center gap-3">
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
		<!-- 4 KPI cards. Responsive: 1 col mobile, 2 cols tablet, 4 cols desktop. -->
		<div class="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
			<KpiCard
				label="Total tickets"
				value={data.kpis.totalTickets}
				icon={KPI_ICONS.totalTickets}
			/>
			<KpiCard
				label="Open tickets"
				value={data.kpis.openTickets}
				icon={KPI_ICONS.openTickets}
				hint="{data.kpis.openTickets} open"
			/>
			<KpiCard
				label="Total spent"
				value={data.kpis.totalSpentEur}
				icon={KPI_ICONS.totalSpent}
			/>
			<KpiCard
				label="Avg ticket value"
				value={data.kpis.avgTicketEur}
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

		<RecentTicketsTable tickets={data.recentTickets} />
	{/if}
</section>
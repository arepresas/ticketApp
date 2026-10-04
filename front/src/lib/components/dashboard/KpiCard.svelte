<script lang="ts">
	import { Ticket, TicketCheck, Wallet, TrendingUp } from '@lucide/svelte';
	import Card from '../ui/card/Card.svelte';
	import CardContent from '../ui/card/CardContent.svelte';
	import { cn } from '../../utils';

	// Narrow union of the lucide icons this component actually receives.
	// Keeps the prop surface tight without dragging in lucide's full Component type.
	export type KpiIconName = 'Ticket' | 'TicketCheck' | 'Wallet' | 'TrendingUp';

	/**
	 * How to render {@link Props.value}.
	 *
	 * `count`  — a plain integer, grouped by thousands (`1,234`).
	 * `money`  — an amount, prefixed with the symbol for {@link Props.currency}.
	 * `raw`    — rendered as-is.
	 *
	 * This replaces a heuristic that sniffed the label with
	 * `/spent|ticket|value/i` and therefore rendered "Total tickets" as
	 * `€42.00` and "Open tickets" as `€7.00` — the word "ticket" matched
	 * a label whose value is a count. The caller knows which is which;
	 * making it say so removes the guessing.
	 */
	export type KpiFormat = 'count' | 'money' | 'raw';

	const ICONS = { Ticket, TicketCheck, Wallet, TrendingUp } as const;

	type Props = {
		label: string;
		value: string | number;
		/** Defaults to `count` — the safer of the two for a KPI card. */
		format?: KpiFormat;
		/** ISO 4217 code, required when `format` is `money`. */
		currency?: string;
		icon?: KpiIconName;
		hint?: string;
		class?: string;
	};

	let {
		label,
		value,
		format = 'count',
		currency = 'EUR',
		icon,
		hint,
		class: className
	}: Props = $props();

	/**
	 * Symbols for the currencies the dashboard can report. Anything else
	 * falls back to the bare code (`CHF 12.00`) rather than guessing a
	 * symbol — a wrong symbol is worse than a slightly verbose one.
	 */
	const CURRENCY_SYMBOLS: Record<string, string> = {
		EUR: '€',
		USD: '$',
		GBP: '£'
	};

	function formatKpiValue(kind: KpiFormat, v: string | number, code: string): string {
		if (typeof v === 'string') return v;
		if (kind === 'raw') return v.toString();
		if (kind === 'count') return v.toLocaleString('en-GB');
		const symbol = CURRENCY_SYMBOLS[code];
		const amount = v.toLocaleString('en-GB', {
			minimumFractionDigits: 2,
			maximumFractionDigits: 2
		});
		return symbol ? `${symbol}${amount}` : `${code} ${amount}`;
	}

	const displayValue = $derived(formatKpiValue(format, value, currency));
	const IconComp = $derived(icon ? ICONS[icon] : undefined);
</script>

<Card class={cn('w-full', className)}>
	<CardContent class="flex flex-col gap-2 p-5">
		<div class="flex items-start justify-between gap-2">
			<p class="text-xs font-medium uppercase tracking-wider text-muted-foreground">{label}</p>
			{#if IconComp}
				<IconComp class="size-4 shrink-0 text-muted-foreground" aria-hidden="true" />
			{/if}
		</div>
		<p class="text-2xl font-bold tracking-tight tabular-nums sm:text-3xl" data-testid="kpi-value">{displayValue}</p>
		{#if hint}
			<p class="text-xs text-muted-foreground">{hint}</p>
		{/if}
	</CardContent>
</Card>
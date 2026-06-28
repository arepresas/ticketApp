<script lang="ts">
	import { Ticket, TicketCheck, Wallet, TrendingUp } from '@lucide/svelte';
	import Card from '../ui/card/Card.svelte';
	import CardContent from '../ui/card/CardContent.svelte';
	import { cn } from '../../utils';

	// Narrow union of the lucide icons this component actually receives.
	// Keeps the prop surface tight without dragging in lucide's full Component type.
	export type KpiIconName = 'Ticket' | 'TicketCheck' | 'Wallet' | 'TrendingUp';

	const ICONS = { Ticket, TicketCheck, Wallet, TrendingUp } as const;

	type Props = {
		label: string;
		value: string | number;
		icon?: KpiIconName;
		hint?: string;
		class?: string;
	};

	let { label, value, icon, hint, class: className }: Props = $props();

	// Currency formatting lives inline: only used here, no need to share yet.
	function formatKpiValue(label: string, v: string | number): string {
		if (typeof v === 'string') return v;
		const isCurrency = /spent|ticket|value/i.test(label);
		return isCurrency ? `€${v.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}` : v.toString();
	}

	const displayValue = $derived(formatKpiValue(label, value));
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
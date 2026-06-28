<script lang="ts">
	// Side-effect import: registers Chart.js controllers/scales/elements once.
	// Do NOT call Chart.register from here — let register.ts own the registry.
	import '../../charts/register';

	import { Line } from 'svelte-chartjs';
	import type { ChartData, ChartOptions } from 'chart.js';

	import type { TicketsPerMonth } from '../../api/dashboard';
	import Card from '../ui/card/Card.svelte';
	import CardContent from '../ui/card/CardContent.svelte';
	import CardHeader from '../ui/card/CardHeader.svelte';
	import CardTitle from '../ui/card/CardTitle.svelte';

	type Props = {
		data: TicketsPerMonth[];
	};

	let { data }: Props = $props();

	// Pull the primary color from the live CSS theme so the chart line + fill
	// stay in sync with app.css tokens. Computed once at module load — the
	// theme is static for the page lifetime and SSR would otherwise hand us
	// empty strings.
	const themePrimary = (() => {
		if (typeof window === 'undefined') return '#22c55e';
		const raw = getComputedStyle(document.documentElement)
			.getPropertyValue('--color-primary')
			.trim();
		return raw || '#22c55e';
	})();

	// Translucent fill for the area under the line. Same hue as the stroke,
	// alpha dropped to ~15% so gridlines stay legible.
	const themePrimaryFill = (() => {
		// oklch / hsl / named → rgba isn't worth a parser at runtime; fall back
		// to a fixed translucent emerald that matches the primary oklch tone.
		return 'rgba(34, 197, 94, 0.15)';
	})();

	// Build the chart.js dataset shape from our domain type. Typed via
	// ChartData<'line'>; the `as any` cast below is the ONLY `any` in this
	// file and is confined to the svelte-chartjs boundary, which types its
	// `data` prop loosely upstream.
	const chartData = $derived<ChartData<'line', number[], string>>({
		labels: data.map((d) => d.month),
		datasets: [
			{
				label: 'Tickets',
				data: data.map((d) => d.count),
				borderColor: themePrimary,
				backgroundColor: themePrimaryFill,
				tension: 0.3,
				fill: true,
				pointRadius: 3,
				pointHoverRadius: 5,
				borderWidth: 2
			}
		]
	});

	const chartOptions: ChartOptions<'line'> = {
		responsive: true,
		maintainAspectRatio: false,
		plugins: {
			legend: { display: true, position: 'top' },
			tooltip: { enabled: true }
		},
		scales: {
			x: { grid: { display: false } },
			y: { beginAtZero: true, ticks: { precision: 0 } }
		}
	};
</script>

<Card class="w-full">
	<CardHeader class="pb-2">
		<CardTitle>Tickets per month</CardTitle>
	</CardHeader>
	<CardContent>
		<!-- Sized wrapper: chart.js needs an explicit height when
		     `maintainAspectRatio: false` is set. -->
		<div class="h-64 w-full">
			<!-- eslint-disable-next-line @typescript-eslint/no-explicit-any -->
			<Line data={chartData as any} options={chartOptions} />
		</div>
	</CardContent>
</Card>

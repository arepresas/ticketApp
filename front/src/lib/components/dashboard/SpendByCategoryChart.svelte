<script lang="ts">
	// Side-effect import: registers Chart.js controllers/elements/scales once.
	// Don't call Chart.register here — `register.ts` owns that contract.
	import '../../charts/register';

	import { Doughnut } from 'svelte-chartjs';
	import type { ChartData, ChartOptions } from 'chart.js';

	import type { SpendByCategory } from '../../api/dashboard';
	import Card from '../ui/card/Card.svelte';
	import CardContent from '../ui/card/CardContent.svelte';

	// Tailwind theme tokens — chosen for contrast against the card background.
	// Order MUST match the domain categories (transport / food / lodging / other)
	// so the slice color stays stable regardless of array order in `data`.
	const CATEGORY_COLORS: Record<SpendByCategory['category'], string> = {
		transport: 'oklch(0.685 0.169 237.323)', // sky-500
		food: 'oklch(0.769 0.188 70.08)', // amber-500
		lodging: 'oklch(0.606 0.25 292.717)', // violet-500
		other: 'oklch(0.552 0.016 285.938)' // zinc-500
	};

	type Props = { data: SpendByCategory[] };

	let { data }: Props = $props();

	// Construct chart.js types with the strict `'doughnut'` generic so the
	// dataset/options match what the controller expects. No widening cast at
	// the wrapper boundary — svelte-chartjs's `<Doughnut>` accepts the
	// already-refined generic.
	const chartData: ChartData<'doughnut', number[], string> = $derived({
		labels: data.map((d) => d.category),
		datasets: [
			{
				data: data.map((d) => d.amountEur),
				backgroundColor: data.map((d) => CATEGORY_COLORS[d.category])
			}
		]
	});

	const chartOptions: ChartOptions<'doughnut'> = {
		responsive: true,
		maintainAspectRatio: false,
		plugins: {
			legend: { position: 'bottom' },
			tooltip: {
				callbacks: {
					// Keep the € formatting local — single chart, no shared util yet.
					label: (ctx) => {
						const value = typeof ctx.parsed === 'number' ? ctx.parsed : 0;
						return `${ctx.label}: €${value.toLocaleString('en-US', {
							minimumFractionDigits: 2,
							maximumFractionDigits: 2
						})}`;
					}
				}
			}
		}
	};
</script>

<Card class="w-full">
	<CardContent class="flex flex-col gap-4 p-5">
		<h3 class="text-sm font-semibold tracking-tight">Spend by category</h3>
		<div class="h-64">
			<Doughnut data={chartData} options={chartOptions} />
		</div>
	</CardContent>
</Card>
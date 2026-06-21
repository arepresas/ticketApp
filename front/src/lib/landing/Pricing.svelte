<script lang="ts">
	import { Check } from '@lucide/svelte';

	const tiers = [
		{
			name: 'Free', price: '€0', period: 'forever', desc: 'Perfect for getting started.',
			features: ['50 receipts / month', 'Local OCR (on-device)', '12 months of history', 'CSV export', 'Community support'],
			cta: 'Start free', href: '#', hl: false
		},
		{
			name: 'Pro', price: '€3', period: '/ month', desc: 'The app most households pick.',
			features: ['Unlimited receipts', 'Cloud OCR (40+ langs)', 'Multi-device sync', 'Encrypted backups', 'Warranty tracker', 'PDF + JSON exports', 'Priority support'],
			cta: 'Start 14-day trial', href: '#', hl: true
		},
		{
			name: 'Self-hosted', price: 'Free', period: 'forever', desc: 'Run on your own server.',
			features: ['Everything in Pro', 'Your hardware, your data', 'Docker one-liner', 'Custom API integrations', 'Community Discord'],
			cta: 'Read the docs', href: '#', hl: false
		}
	];
</script>

<section id="pricing" class="border-b border-border/60 py-14 sm:py-20">
	<div class="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
		<div class="mx-auto max-w-xl text-center">
			<h2 class="text-balance text-3xl font-bold tracking-tight sm:text-4xl">Free for the curious. <span class="text-primary">Honest for power users.</span></h2>
			<p class="mt-3 text-pretty text-base text-muted-foreground">No ads, no data sales, no surprise charges.</p>
		</div>

		<div class="mt-10 grid grid-cols-1 gap-5 sm:grid-cols-3 lg:mt-14">
			{#each tiers as t (t.name)}
				<div class="relative flex flex-col rounded-2xl border bg-card p-6 shadow-sm {t.hl ? 'border-primary shadow-lg shadow-primary/10' : 'border-border'}">
					{#if t.hl}
						<span class="absolute -top-2.5 left-1/2 -translate-x-1/2 rounded-full bg-primary px-3 py-0.5 text-[10px] font-semibold text-primary-foreground">Most popular</span>
					{/if}
					<h3 class="text-xs font-semibold uppercase tracking-wider text-muted-foreground">{t.name}</h3>
					<div class="mt-3 flex items-baseline gap-0.5">
						<span class="text-3xl font-bold">{t.price}</span>
						<span class="text-xs text-muted-foreground">{t.period}</span>
					</div>
					<p class="mt-1.5 text-xs text-muted-foreground">{t.desc}</p>
					<ul class="mt-5 flex-1 space-y-2 text-xs">
						{#each t.features as f (f)}
							<li class="flex items-start gap-2">
								<Check class="mt-0.5 size-3.5 shrink-0 text-primary" />
								<span>{f}</span>
							</li>
						{/each}
					</ul>
					<a href={t.href} class="mt-6 flex h-10 items-center justify-center rounded-lg text-sm font-medium transition-colors {t.hl ? 'bg-primary text-primary-foreground hover:bg-primary/90' : 'border border-border hover:bg-accent'}">
						{t.cta}
					</a>
				</div>
			{/each}
		</div>
	</div>
</section>

<script lang="ts">
	import { ShoppingCart, Pill, Utensils, Fuel, ShoppingBag } from '@lucide/svelte';

	const cats = [
		{ icon: ShoppingCart, title: 'Supermarkets', desc: 'Weekly groceries from any chain. Items parsed, totals verified, categories inferred.', tag: 'Most used' },
		{ icon: Pill, title: 'Pharmacies', desc: 'Track prescriptions, OTC meds and parapharmacy. Export reimbursement-ready PDFs.', tag: 'Health' },
		{ icon: Utensils, title: 'Restaurants & cafés', desc: 'Tabs, tips, group dinners. Split bills and keep a personal food diary automatically.', tag: 'Lifestyle' },
		{ icon: Fuel, title: 'Fuel stations', desc: 'Log every fill-up. Spot cheaper stations and track vehicle consumption over time.', tag: 'Auto' },
		{ icon: ShoppingBag, title: 'Everything else', desc: 'Hardware, clothing, electronics, services. If it has a receipt, we handle it.', tag: 'Generic' }
	];
</script>

<section id="categories" class="border-b border-border/60 bg-muted/30 py-14 sm:py-20">
	<div class="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
		<div class="mx-auto max-w-xl text-center">
			<h2 class="text-balance text-3xl font-bold tracking-tight sm:text-4xl">One app. <span class="text-primary">Every purchase.</span></h2>
			<p class="mt-3 text-pretty text-base text-muted-foreground">Specialised handling for the categories you actually buy from, with a smart fallback for everything else.</p>
		</div>

		<div class="mt-10 grid grid-cols-1 gap-3 sm:grid-cols-2 lg:mt-14 lg:grid-cols-3 xl:grid-cols-5">
			{#each cats as c (c.title)}
				<div class="group flex flex-col gap-3 rounded-xl border border-border bg-card p-4 shadow-sm transition-all hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-md">
					<div class="flex items-center justify-between">
						<span class="flex size-9 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
							<c.icon class="size-4.5" />
						</span>
						<span class="rounded-full border border-border px-2 py-0.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">{c.tag}</span>
					</div>
					<h3 class="text-sm font-semibold">{c.title}</h3>
					<p class="text-xs text-muted-foreground">{c.desc}</p>
				</div>
			{/each}
		</div>
	</div>
</section>

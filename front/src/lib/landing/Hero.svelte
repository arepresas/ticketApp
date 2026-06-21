<script lang="ts">
	import { ArrowRight, Play, Star } from '@lucide/svelte';

	const items = [
		{ name: 'Aceite oliva virgen extra 1L', qty: 1, price: '6.49' },
		{ name: 'Pollo entero de corral 2kg', qty: 1, price: '7.95' },
		{ name: 'Tomates pera kg', qty: '1.42 kg', price: '2.84' },
		{ name: 'Pasta integral 500g', qty: 2, price: '2.30' },
		{ name: 'Yogur griego pack', qty: 6, price: '3.60' }
	];
</script>

<section id="hero" class="relative overflow-hidden border-b border-border/60">
	<!-- subtle bg -->
	<div class="pointer-events-none absolute inset-0 -z-10 bg-gradient-to-b from-primary/5 to-transparent" aria-hidden="true"></div>

	<div class="mx-auto grid w-full max-w-6xl gap-10 px-4 py-14 sm:px-6 sm:py-20 lg:grid-cols-2 lg:gap-14 lg:py-24 lg:px-8">
		<!-- Left: copy -->
		<div class="flex flex-col justify-center">
			<span class="mb-4 inline-flex items-center gap-1.5 rounded-full border border-border bg-background px-3 py-1 text-xs font-medium text-muted-foreground">
				<span class="relative flex size-1.5"><span class="absolute inset-0 animate-ping rounded-full bg-emerald-500 opacity-75"></span><span class="relative inline-flex size-1.5 rounded-full bg-emerald-500"></span></span>
				Live beta — local-first, your data stays yours
			</span>

			<h1 class="text-balance text-4xl font-bold tracking-tight sm:text-5xl lg:text-5xl xl:text-6xl">
				Every receipt. <span class="text-primary">One searchable place.</span>
			</h1>

			<p class="mt-4 max-w-lg text-pretty text-base text-muted-foreground sm:text-lg">
				TicketApp turns paper and digital receipts into a tidy, searchable timeline. From supermarket runs to pharmacy pickups, restaurant tabs and fuel stops — stop losing warranties and price comparisons.
			</p>

			<div class="mt-6 flex flex-col gap-2.5 sm:flex-row">
				<a href="#cta" class="group inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-primary px-6 text-sm font-medium text-primary-foreground shadow hover:bg-primary/90 transition-colors sm:w-auto">
					Start tracking free
					<ArrowRight class="size-4 transition-transform group-hover:translate-x-0.5" />
				</a>
				<a href="#how-it-works" class="inline-flex h-11 items-center justify-center gap-2 rounded-lg border border-border bg-background px-6 text-sm font-medium transition-colors hover:bg-accent sm:w-auto">
					<Play class="size-4 text-primary" />
					See how it works
				</a>
			</div>

			<div class="mt-8 flex items-center gap-3 border-t border-border/60 pt-6">
				<div class="flex -space-x-1.5" aria-hidden="true">
					{#each Array(4) as _, i (i)}
						<span class="flex size-7 items-center justify-center rounded-full border-2 border-background bg-muted text-[10px] font-semibold text-muted-foreground" style="background-color: {['#f43f5e','#f59e0b','#10b981','#3b82f6'][i]}; color: white;">{['AM','JL','SC','DP'][i]}</span>
					{/each}
				</div>
				<div class="text-xs text-muted-foreground">
					<div class="flex items-center gap-0.5 text-amber-500" aria-label="4.8 out of 5 stars">
						{#each Array(5) as _, i (i)}<Star class="size-3 fill-current" />{/each}
					</div>
					<p class="mt-0.5">Loved by <span class="font-semibold text-foreground">2,400+</span> households</p>
				</div>
			</div>
		</div>

		<!-- Right: receipt mockup -->
		<div class="relative flex items-center justify-center">
			<div class="absolute -inset-6 -z-10 rounded-3xl bg-gradient-to-br from-primary/15 to-transparent blur-xl" aria-hidden="true"></div>

			<div class="w-full max-w-sm rounded-2xl border border-border bg-card p-0.5 shadow-xl">
				<div class="rounded-xl bg-card p-5">
					<div class="flex items-start justify-between border-b border-dashed border-border pb-3">
						<div>
							<p class="text-[10px] uppercase tracking-wider text-muted-foreground">Receipt</p>
							<p class="mt-0.5 text-base font-semibold">Mercadona S.A.</p>
							<p class="text-[10px] text-muted-foreground">Madrid, C/Gran Vía 102</p>
						</div>
						<span class="rounded-full border border-border bg-secondary px-2 py-0.5 font-mono text-[10px] text-secondary-foreground">#TR-2487</span>
					</div>

					<ul class="divide-y divide-border text-xs">
						{#each items as item (item.name)}
							<li class="flex items-center justify-between py-1.5">
								<span class="text-foreground">{item.name} <span class="text-muted-foreground">×{item.qty}</span></span>
								<span class="font-mono tabular-nums">{item.price} €</span>
							</li>
						{/each}
					</ul>

					<div class="mt-3 space-y-1 border-t border-dashed border-border pt-3 text-xs">
						<div class="flex justify-between text-muted-foreground"><span>Subtotal</span><span class="font-mono">23.18 €</span></div>
						<div class="flex justify-between text-muted-foreground"><span>IVA (10%)</span><span class="font-mono">2.10 €</span></div>
						<div class="mt-1.5 flex justify-between border-t border-border pt-1.5 text-sm font-semibold"><span>Total</span><span class="font-mono">25.28 €</span></div>
					</div>

					<div class="mt-3 flex items-center justify-between text-[10px] text-muted-foreground">
						<span>12 Mar 2026 · 18:42</span>
						<span class="inline-flex items-center gap-1 rounded-full bg-primary/10 px-2 py-0.5 text-primary"><span class="size-1 rounded-full bg-primary"></span>Groceries</span>
					</div>
				</div>
			</div>
		</div>
	</div>
</section>

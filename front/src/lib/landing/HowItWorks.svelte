<script lang="ts">
	import { Camera, Sparkles, Rocket } from '@lucide/svelte';

	const steps = [
		{ n: '1', icon: Camera, title: 'Snap or forward', desc: 'Take a photo, upload a PDF, or forward a digital receipt from your email. OCR runs locally first.' },
		{ n: '2', icon: Sparkles, title: 'Auto-organise', desc: 'Items, taxes and totals extracted. Categories inferred, and you can fix anything in two taps.' },
		{ n: '3', icon: Rocket, title: 'Search & export', desc: 'Find any purchase in milliseconds. Export CSV for your accountant, PDF or JSON for your tools.' }
	];
</script>

<section id="how-it-works" class="border-b border-border/60 py-14 sm:py-20">
	<div class="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
		<div class="mx-auto max-w-xl text-center">
			<h2 class="text-balance text-3xl font-bold tracking-tight sm:text-4xl">From paper to <span class="text-primary">insight in 3 steps</span></h2>
			<p class="mt-3 text-pretty text-base text-muted-foreground">No spreadsheets, no shoeboxes. Just a clean, queryable history.</p>
		</div>

		<div class="mt-10 grid grid-cols-1 gap-5 sm:grid-cols-3 lg:mt-14">
			{#each steps as s (s.n)}
				<div class="flex flex-col items-center text-center">
					<span class="flex size-12 items-center justify-center rounded-full bg-primary/10 text-primary">
						<s.icon class="size-6" />
					</span>
					<h3 class="mt-4 text-lg font-semibold">{s.title}</h3>
					<p class="mt-2 text-sm text-muted-foreground">{s.desc}</p>
				</div>
			{/each}
		</div>
	</div>
</section>

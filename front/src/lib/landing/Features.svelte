<script lang="ts">
	import { ScanText, FolderTree, Search, Download } from '@lucide/svelte';

	const features = [
		{ icon: ScanText, title: 'Smart OCR', desc: 'Snap a photo or forward an email. On-device OCR extracts merchant, items, taxes and total in under 2 seconds.' },
		{ icon: FolderTree, title: 'Auto-categorisation', desc: 'Receipts are grouped into groceries, pharmacy, fuel, restaurants and a custom taxonomy you control.' },
		{ icon: Search, title: 'Universal search', desc: 'Find any purchase in milliseconds — by merchant, item, price, date or category. Full-text across years.' },
		{ icon: Download, title: 'Export & backup', desc: 'CSV for spreadsheets, PDF for accountants, JSON for developers. End-to-end encrypted backups included.' }
	];
</script>

<section id="features" class="border-b border-border/60 py-14 sm:py-20">
	<div class="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
		<div class="mx-auto max-w-xl text-center">
			<h2 class="text-balance text-3xl font-bold tracking-tight sm:text-4xl">Everything you need to <span class="text-primary">tame your receipts</span></h2>
			<p class="mt-3 text-pretty text-base text-muted-foreground">Built for households, freelancers and small businesses that want clarity without the bookkeeping overhead.</p>
		</div>

		<div class="mt-10 grid grid-cols-1 gap-4 sm:grid-cols-2 lg:mt-14">
			{#each features as f (f.title)}
				<div class="group flex items-start gap-4 rounded-xl border border-border bg-card p-5 transition-all hover:border-primary/40 hover:shadow-sm">
					<span class="flex size-10 shrink-0 items-center justify-center rounded-lg bg-primary/10 text-primary transition-colors group-hover:bg-primary group-hover:text-primary-foreground">
						<f.icon class="size-5" />
					</span>
					<div>
						<h3 class="font-semibold">{f.title}</h3>
						<p class="mt-1 text-sm text-muted-foreground">{f.desc}</p>
					</div>
				</div>
			{/each}
		</div>
	</div>
</section>

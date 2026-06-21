<script lang="ts">
	const items = [
		{ q: 'Is my data private?', a: 'Yes. OCR runs on-device by default. Receipts are end-to-end encrypted before sync. We never sell or share your data. The code is AGPL-3.0 — audit it yourself.' },
		{ q: 'Which stores are supported?', a: 'Any store, any format. Specialised parsers for Mercadona, Carrefour, Lidl, Aldi, Tesco, Walmart and more. Generic OCR handles everything else.' },
		{ q: 'How accurate is the OCR?', a: 'Over 98% on thermal receipts, ~96% on A4. Handwritten best-effort. You can always correct any field and the model learns.' },
		{ q: 'What export formats are available?', a: 'CSV, PDF and JSON. All exports generated client-side — your data never leaves your machine. Monthly email digest available on Pro.' },
		{ q: 'How much does it cost?', a: 'Free for 50 receipts/month. Pro is €3/month for unlimited. Self-hosted is free forever. No hidden fees, no seat pricing.' }
	];
</script>

<section id="faq" class="border-b border-border/60 bg-muted/30 py-14 sm:py-20">
	<div class="mx-auto max-w-2xl px-4 sm:px-6 lg:px-8">
		<div class="text-center">
			<h2 class="text-3xl font-bold tracking-tight sm:text-4xl">Frequently asked questions</h2>
			<p class="mt-3 text-base text-muted-foreground">Can't find what you need? Reach out on GitHub — a human replies within a day.</p>
		</div>

		<div class="mt-10 overflow-hidden rounded-xl border border-border bg-card">
			{#each items as item, i (item.q)}
				<details class="group border-b border-border last:border-b-0" open={i === 0}>
					<summary class="flex cursor-pointer list-none items-center justify-between gap-3 px-5 py-4 text-sm font-medium hover:bg-accent/50 [&::-webkit-details-marker]:hidden">
						<span>{item.q}</span>
						<span class="flex size-5 shrink-0 items-center justify-center rounded-full border border-border text-muted-foreground transition-transform duration-150 group-open:rotate-45 group-open:border-primary group-open:text-primary">
							<svg class="size-3" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
						</span>
					</summary>
					<div class="px-5 pb-4 text-sm leading-relaxed text-muted-foreground">{item.a}</div>
				</details>
			{/each}
		</div>
	</div>
</section>

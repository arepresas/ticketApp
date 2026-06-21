<script lang="ts">
	import { ArrowRight, Check } from '@lucide/svelte';
	import ReceiptText from '@lucide/svelte/icons/receipt-text';

	const year = new Date().getFullYear();

	const cols = [
		{ title: 'Product', links: [{ h: '#features', l: 'Features' }, { h: '#how-it-works', l: 'How it works' }, { h: '#pricing', l: 'Pricing' }, { h: '#faq', l: 'FAQ' }] },
		{ title: 'Resources', links: [{ h: '#', l: 'Documentation' }, { h: '#', l: 'Self-host guide' }, { h: '#', l: 'API reference' }] },
		{ title: 'Legal', links: [{ h: '#', l: 'Privacy' }, { h: '#', l: 'Terms' }, { h: '#', l: 'License (AGPL-3.0)' }] }
	];
</script>

<section id="cta" class="py-14 sm:py-20">
	<div class="mx-auto max-w-6xl px-4 sm:px-6 lg:px-8">
		<div class="rounded-2xl border border-border bg-card p-8 text-center shadow-sm sm:p-12">
			<h2 class="text-balance text-3xl font-bold tracking-tight sm:text-4xl">Ready to tame your <span class="text-primary">receipts</span>?</h2>
			<p class="mt-3 text-base text-muted-foreground">Start free. No credit card. Your data, your machine, your rules.</p>

			<form class="mx-auto mt-6 flex max-w-md flex-col gap-2 sm:flex-row" onsubmit={(e) => e.preventDefault()}>
				<label class="sr-only" for="cta-email">Email address</label>
				<input id="cta-email" type="email" placeholder="you@example.com" autocomplete="email" class="h-11 flex-1 rounded-lg border border-input bg-background px-4 text-sm placeholder:text-muted-foreground focus:outline-none focus:ring-2 focus:ring-ring" />
				<button type="submit" class="inline-flex h-11 items-center justify-center gap-2 rounded-lg bg-primary px-6 text-sm font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90">
					Start free <ArrowRight class="size-4" />
				</button>
			</form>

			<ul class="mt-4 flex flex-wrap items-center justify-center gap-x-5 gap-y-1 text-xs text-muted-foreground">
				<li class="inline-flex items-center gap-1"><Check class="size-3.5 text-primary" />No credit card</li>
				<li class="inline-flex items-center gap-1"><Check class="size-3.5 text-primary" />Cancel anytime</li>
				<li class="inline-flex items-center gap-1"><Check class="size-3.5 text-primary" />Free up to 50/mo</li>
			</ul>
		</div>
	</div>
</section>

<footer class="border-t border-border bg-muted/30">
	<div class="mx-auto max-w-6xl px-4 py-10 sm:px-6 sm:py-12 lg:px-8">
		<div class="grid grid-cols-2 gap-8 sm:grid-cols-2 md:grid-cols-5">
			<div class="col-span-2 md:col-span-2">
				<a href="#hero" class="inline-flex items-center gap-2 font-semibold">
					<span class="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground"><ReceiptText class="size-4" /></span>
					<span>TicketApp</span>
				</a>
				<p class="mt-3 max-w-xs text-sm text-muted-foreground">Every receipt. One searchable place. Open source, local-first, privacy-first.</p>
				<a href="https://github.com" target="_blank" rel="noreferrer noopener" class="mt-4 inline-flex items-center gap-1.5 text-sm text-muted-foreground hover:text-foreground transition-colors">
					<svg class="size-4" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" clip-rule="evenodd" d="M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.09.68-.22.68-.49 0-.24-.01-.88-.01-1.73-2.78.62-3.37-1.36-3.37-1.36-.45-1.18-1.11-1.49-1.11-1.49-.91-.63.07-.62.07-.62 1 .07 1.53 1.05 1.53 1.05.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.55-1.14-4.55-5.07 0-1.12.39-2.04 1.03-2.76-.1-.26-.45-1.3.1-2.71 0 0 .84-.27 2.75 1.05A9.41 9.41 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.91-1.32 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.64 1.03 2.76 0 3.94-2.34 4.81-4.57 5.07.36.32.68.94.68 1.9 0 1.37-.01 2.48-.01 2.81 0 .27.18.59.69.49C19.14 20.62 22 16.78 22 12.25 22 6.58 17.52 2 12 2Z" /></svg> Open source
				</a>
			</div>
			{#each cols as col (col.title)}
				<div>
					<h3 class="text-sm font-semibold">{col.title}</h3>
					<ul class="mt-3 space-y-2">
						{#each col.links as link (link.l)}
							<li><a href={link.h} class="text-sm text-muted-foreground hover:text-foreground transition-colors">{link.l}</a></li>
						{/each}
					</ul>
				</div>
			{/each}
		</div>
		<div class="mt-10 flex flex-col items-start justify-between gap-3 border-t border-border pt-6 text-xs text-muted-foreground sm:flex-row sm:items-center">
			<p>&copy; {year} TicketApp. AGPL-3.0.</p>
			<p>Built with Svelte 5 &amp; Tailwind v4</p>
		</div>
	</div>
</footer>

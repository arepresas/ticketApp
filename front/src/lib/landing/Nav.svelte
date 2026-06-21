<script lang="ts">
	import { Menu, X } from '@lucide/svelte';
	import ReceiptText from '@lucide/svelte/icons/receipt-text';
	import ThemeToggle from './ThemeToggle.svelte';

	const links = [
		{ href: '#features', label: 'Features' },
		{ href: '#how-it-works', label: 'How it works' },
		{ href: '#pricing', label: 'Pricing' },
		{ href: '#faq', label: 'FAQ' }
	];

	let dialog: HTMLDialogElement = $state(null!);

	function openMenu() { dialog?.showModal(); }
	function closeMenu() { dialog?.close(); }
</script>

<header class="sticky top-0 z-40 w-full border-b border-border/60 bg-background/80 backdrop-blur-md">
	<div class="mx-auto flex h-14 w-full max-w-6xl items-center justify-between gap-3 px-4 sm:px-6 lg:px-8">
		<a href="#hero" class="flex items-center gap-2 font-semibold" aria-label="TicketApp home">
			<span class="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground">
				<ReceiptText class="size-4" />
			</span>
			<span class="text-sm">TicketApp</span>
		</a>

		<nav class="hidden items-center gap-0.5 md:flex" aria-label="Primary">
			{#each links as link (link.href)}
				<a href={link.href} class="rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground">
					{link.label}
				</a>
			{/each}
		</nav>

		<div class="flex items-center gap-1">
			<ThemeToggle />
			<a href="https://github.com" class="hidden size-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground sm:inline-flex" aria-label="GitHub" target="_blank" rel="noreferrer noopener">
				<svg class="size-4" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path fill-rule="evenodd" clip-rule="evenodd" d="M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.09.68-.22.68-.49 0-.24-.01-.88-.01-1.73-2.78.62-3.37-1.36-3.37-1.36-.45-1.18-1.11-1.49-1.11-1.49-.91-.63.07-.62.07-.62 1 .07 1.53 1.05 1.53 1.05.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.55-1.14-4.55-5.07 0-1.12.39-2.04 1.03-2.76-.1-.26-.45-1.3.1-2.71 0 0 .84-.27 2.75 1.05A9.41 9.41 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.91-1.32 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.64 1.03 2.76 0 3.94-2.34 4.81-4.57 5.07.36.32.68.94.68 1.9 0 1.37-.01 2.48-.01 2.81 0 .27.18.59.69.49C19.14 20.62 22 16.78 22 12.25 22 6.58 17.52 2 12 2Z" /></svg>
			</a>
			<a href="#cta" class="hidden h-8 items-center rounded-md bg-primary px-4 text-xs font-medium text-primary-foreground shadow transition-colors hover:bg-primary/90 sm:inline-flex">Start free</a>

			<button type="button" onclick={openMenu} aria-label="Open menu" class="inline-flex size-8 items-center justify-center rounded-md text-foreground hover:bg-accent md:hidden">
				<Menu class="size-4" />
			</button>
		</div>
	</div>
</header>

<dialog bind:this={dialog} class="drawer" aria-label="Mobile navigation" onclick={(e) => { if ((e.target as HTMLElement).tagName === 'A') dialog?.close(); }}>
	<div class="drawer-panel ml-auto flex h-full w-full max-w-xs flex-col gap-5 border-l border-border bg-background p-6 shadow-xl">
		<div class="flex items-center justify-between">
			<div class="flex items-center gap-2 font-semibold text-sm">
				<span class="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground"><ReceiptText class="size-4" /></span>
				TicketApp
			</div>
			<button type="button" onclick={closeMenu} aria-label="Close menu" class="size-8 flex items-center justify-center rounded-md text-foreground hover:bg-accent">
				<X class="size-4" />
			</button>
		</div>
		<nav class="flex flex-col gap-0.5" aria-label="Mobile">
			{#each links as link (link.href)}
				<a href={link.href} class="rounded-md px-3 py-2 text-sm font-medium hover:bg-accent">{link.label}</a>
			{/each}
		</nav>
		<div class="mt-auto space-y-2">
			<a href="#cta" onclick={closeMenu} class="flex h-10 items-center justify-center rounded-lg bg-primary text-sm font-medium text-primary-foreground">Start free</a>
			<p class="text-center text-xs text-muted-foreground">No credit card. Cancel anytime.</p>
		</div>
	</div>
</dialog>

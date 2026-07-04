<script lang="ts">
	/**
	 * App shell header.
	 *
	 * Mounted once at the top of `<body>` (see `front/src/index.ts` →
	 * `mount(Header, …)`). Lives in the light DOM so Tailwind utilities from
	 * `app.css` apply directly without a shadow-DOM stylesheet injection.
	 *
	 * The header adapts to the auth state read from the shared `auth` store:
	 *  - Public mode (signed-out): logo + marketing anchors (Features, How it
	 *    works, Pricing, FAQ) + theme toggle + GitHub link + Google login.
	 *  - Authenticated mode: logo + app anchors (Tickets, Nuevo, Dashboard) +
	 *    theme toggle + the user avatar / sign-out menu provided by
	 *    `GoogleLoginButton` (which already branches on the same store).
	 *
	 * Anchors:
	 *  - Public links use fragment ids (`#features`, …) that exist on the
	 *    landing page. They resolve to top-of-page when the user is
	 *    authenticated and the landing subtree isn't mounted, which is the
	 *    expected no-op behaviour for those routes.
	 *  - Authenticated links point at `#main` (the dashboard wrapper's main
	 *    scroll target) for now. Concrete routes will replace them once the
	 *    router exists.
	 */
	import { Menu, X } from '@lucide/svelte';
	import ReceiptText from '@lucide/svelte/icons/receipt-text';

	import { auth } from '../../auth/store.svelte';
	import GoogleLoginButton from '../../auth/GoogleLoginButton.svelte';
	import ThemeToggle from '../../landing/ThemeToggle.svelte';

	// Public mode — marketing anchors. Order matters; rendered left-to-right.
	const publicLinks: ReadonlyArray<{ href: string; label: string; onClick?: (e: MouseEvent) => void }> =
		[
			{ href: '#features', label: 'Features' },
			{ href: '#how-it-works', label: 'How it works' },
			{ href: '#pricing', label: 'Pricing' },
			{ href: '#faq', label: 'FAQ' }
		];

	// Authenticated mode — app anchors. Replace with router routes later.
	const appLinks: ReadonlyArray<{ href: string; label: string; onClick?: (e: MouseEvent) => void }> =
		[
			{ href: '#pending', label: 'Pending tickets', onClick: openPendingTickets },
			{ href: '#new', label: 'New', onClick: openNewTicket },
			{ href: '#main', label: 'Dashboard' }
		];

	const googleClientId = import.meta.env.VITE_GOOGLE_CLIENT_ID ?? '';

	let dialog: HTMLDialogElement = $state(null!);

	const links = $derived(auth.isAuthenticated ? appLinks : publicLinks);

	/**
	 * Open the "Pending tickets" list by setting `body.is-pending`.
	 * Mirrors `openNewTicket` — the hash gives us a bookmarkable URL
	 * and `preventDefault` keeps the browser from scrolling to a
	 * missing anchor. Also fires a `pending:open` CustomEvent so the
	 * `<pending-tickets-app>` shell can react — the body class toggle
	 * alone is invisible to Svelte's reactivity (the DOM classList is
	 * not a reactive source), so the screen needs a DOM-level signal
	 * to trigger its initial fetch.
	 */
	function openPendingTickets(e: MouseEvent): void {
		e.preventDefault();
		window.location.hash = 'pending';
		document.body.classList.add('is-pending');
		document.body.classList.remove('is-new');
		window.dispatchEvent(new CustomEvent('pending:open'));
	}

	/**
	 * Open the "New ticket" upload screen by setting `body.is-new`. The
	 * hash gives us a real URL the user can bookmark/back-button to; the
	 * body class is what the index.html CSS gate reads. `preventDefault`
	 * stops the browser from jumping the scroll position to a missing
	 * `#new` anchor.
	 */
	function openNewTicket(e: MouseEvent): void {
		e.preventDefault();
		window.location.hash = 'new';
		document.body.classList.add('is-new');
		document.body.classList.remove('is-pending');
	}

	function openMenu(): void {
		dialog?.showModal();
	}
	function closeMenu(): void {
		dialog?.close();
	}
</script>

<header
	class="sticky top-0 z-40 w-full border-b border-border/60 bg-background/80 backdrop-blur-md"
>
	<div
		class="mx-auto flex h-14 w-full max-w-6xl items-center justify-between gap-3 px-4 sm:px-6 lg:px-8"
	>
		<a href="#main" class="flex items-center gap-2 font-semibold" aria-label="TicketApp home">
			<span
				class="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground"
			>
				<ReceiptText class="size-4" />
			</span>
			<span class="text-sm">TicketApp</span>
		</a>

		<nav class="hidden items-center gap-0.5 md:flex" aria-label="Primary">
			{#each links as link (link.label)}
				<a
					href={link.href}
					onclick={link.onClick}
					class="rounded-md px-3 py-1.5 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-foreground"
				>
					{link.label}
				</a>
			{/each}
		</nav>

		<div class="flex items-center gap-2">
			<div data-testid="theme-toggle">
				<ThemeToggle />
			</div>
			{#if !auth.isAuthenticated}
				<a
					href="https://github.com"
					class="hidden size-8 items-center justify-center rounded-md text-muted-foreground transition-colors hover:bg-accent hover:text-foreground sm:inline-flex"
					aria-label="GitHub"
					target="_blank"
					rel="noreferrer noopener"
				>
					<svg class="size-4" viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"
						><path
							fill-rule="evenodd"
							clip-rule="evenodd"
							d="M12 2C6.48 2 2 6.58 2 12.25c0 4.53 2.87 8.37 6.84 9.73.5.09.68-.22.68-.49 0-.24-.01-.88-.01-1.73-2.78.62-3.37-1.36-3.37-1.36-.45-1.18-1.11-1.49-1.11-1.49-.91-.63.07-.62.07-.62 1 .07 1.53 1.05 1.53 1.05.89 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.63-1.37-2.22-.26-4.55-1.14-4.55-5.07 0-1.12.39-2.04 1.03-2.76-.1-.26-.45-1.3.1-2.71 0 0 .84-.27 2.75 1.05A9.41 9.41 0 0 1 12 6.84c.85.01 1.71.12 2.51.34 1.91-1.32 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.64 1.03 2.76 0 3.94-2.34 4.81-4.57 5.07.36.32.68.94.68 1.9 0 1.37-.01 2.48-.01 2.81 0 .27.18.59.69.49C19.14 20.62 22 16.78 22 12.25 22 6.58 17.52 2 12 2Z"
						/></svg
					>
				</a>
			{/if}
			{#if googleClientId}
				<!-- Always rendered: GoogleLoginButton branches internally on
				     auth.isAuthenticated (login button when signed-out, avatar +
				     sign-out menu when signed-in). Mounting it always keeps the
				     component instance alive so the avatar survives the auth
				     state flip without a remount cycle. -->
				<div class="hidden sm:inline-flex" data-testid="google-login-button">
					<GoogleLoginButton clientId={googleClientId} />
				</div>
			{/if}

			<button
				type="button"
				onclick={openMenu}
				aria-label="Open menu"
				class="inline-flex size-8 items-center justify-center rounded-md text-foreground hover:bg-accent md:hidden"
			>
				<Menu class="size-4" />
			</button>
		</div>
	</div>
</header>

<dialog
	bind:this={dialog}
	class="drawer"
	aria-label="Mobile navigation"
	onclick={(e) => {
		if ((e.target as HTMLElement).tagName === 'A') dialog?.close();
	}}
>
	<div
		class="drawer-panel ml-auto flex h-full w-full max-w-xs flex-col gap-5 border-l border-border bg-background p-6 shadow-xl"
	>
		<div class="flex items-center justify-between">
			<div class="flex items-center gap-2 font-semibold text-sm">
				<span
					class="flex size-8 items-center justify-center rounded-lg bg-primary text-primary-foreground"
					><ReceiptText class="size-4" /></span
				>
				TicketApp
			</div>
			<button
				type="button"
				onclick={closeMenu}
				aria-label="Close menu"
				class="size-8 flex items-center justify-center rounded-md text-foreground hover:bg-accent"
			>
				<X class="size-4" />
			</button>
		</div>
		<nav class="flex flex-col gap-0.5" aria-label="Mobile">
			{#each links as link (link.label)}
				<a
					href={link.href}
					onclick={link.onClick}
					class="rounded-md px-3 py-2 text-sm font-medium hover:bg-accent"
				>
					{link.label}
				</a>
			{/each}
		</nav>
		<div class="mt-auto space-y-2">
			{#if googleClientId}
				<div data-testid="google-login-button">
					<GoogleLoginButton clientId={googleClientId} />
				</div>
			{:else if !auth.isAuthenticated}
				<a
					href="#cta"
					onclick={closeMenu}
					class="flex h-10 items-center justify-center rounded-lg bg-primary text-sm font-medium text-primary-foreground"
					>Start free</a
				>
				<p class="text-center text-xs text-muted-foreground">
					No credit card. Cancel anytime.
				</p>
			{/if}
		</div>
	</div>
</dialog>

<svelte:options customElement="dashboard-app" />

<script lang="ts">
	/**
	 * DashboardApp — custom element shell that gates the authenticated
	 * dashboard view.
	 *
	 * Why a custom element (not a plain Svelte component)?
	 * The host page (index.html) mounts this as `<dashboard-app>`. Shadow
	 * DOM isolates Tailwind utilities inside the dashboard subtree — we
	 * inject the compiled stylesheet via {@html} so utility classes on
	 * <Dashboard /> reach the DOM. Same pattern as LandingApp.svelte.
	 *
	 * Auth gate:
	 * - On mount, run `initAuth()` once. The store dedupes concurrent
	 *   bootstrap calls, so calling from here + from the host module is
	 *   safe.
	 * - status === 'loading' → render a minimal skeleton so layout
	 *   doesn't jump when auth resolves. The Dashboard child owns its
	 *   own full skeleton; we render a single full-page card with
	 *   `animate-pulse` only to cover the gate itself.
	 * - isAuthenticated + user → render <Dashboard user={user} />.
	 * - Otherwise render nothing. The host page toggles a body class
	 *   (`is-authenticated`) so the sibling <landing-app> shows up
	 *   instead. We do NOT manage the toggle from here — that's the
	 *   host module's job, single source of truth.
	 *
	 * No logout button here. GoogleLoginButton owns logout UX; this
	 * element is purely the post-login shell.
	 */
	import tailwindCss from '../../app.css?inline';

	import { auth, initAuth } from './store.svelte';
	import Dashboard from '../components/dashboard/Dashboard.svelte';
</script>

<svelte:element this={'style'}>{@html tailwindCss}</svelte:element>

{#if auth.status === 'loading'}
	<div
		class="flex min-h-screen items-center justify-center bg-background font-sans text-foreground antialiased"
		aria-busy="true"
	>
		<div class="flex h-72 w-full max-w-md animate-pulse flex-col gap-4 rounded-xl border border-border bg-card p-6 shadow-sm">
			<div class="h-4 w-1/3 rounded bg-muted"></div>
			<div class="h-3 w-1/2 rounded bg-muted"></div>
			<div class="mt-6 h-24 rounded bg-muted"></div>
			<div class="h-3 w-2/3 rounded bg-muted"></div>
		</div>
	</div>
{:else if auth.isAuthenticated && auth.user}
	<div class="min-h-screen bg-background text-foreground font-sans antialiased">
		<Dashboard user={auth.user} />
	</div>
{/if}
<script lang="ts">
	/**
	 * EffectHost — headless component mounted by host.ts.
	 *
	 * Owns the reactive `$effect` that mirrors `auth.isAuthenticated` onto
	 * `document.body.classList.toggle('is-authenticated', …)`. Exists as a
	 * dedicated component because Svelte 5's `$effect` rune only runs
	 * inside an active component context; mounting a 0-byte component via
	 * `mount()` from `svelte` is the supported way to get a reactive
	 * effect outside the regular component tree (the runtime helper
	 * `effectRoot` exists internally but is not exported from
	 * `svelte/index-client.js`).
	 *
	 * Render output: nothing. The host element is detached.
	 */
	import { auth } from './store.svelte';

	$effect(() => {
		document.body.classList.toggle('is-authenticated', auth.isAuthenticated);
	});
</script>
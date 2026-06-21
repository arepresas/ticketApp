<script lang="ts">
	import { Sun, Moon, Monitor } from '@lucide/svelte';

	type Theme = 'light' | 'dark' | 'system';
	const KEY = 'ticketapp-theme';

	function getStored(): Theme {
		try { const v = localStorage.getItem(KEY); return v === 'light' || v === 'dark' ? v : 'system'; } catch { return 'system'; }
	}
	function resolved(m: Theme) {
		if (m === 'light' || m === 'dark') return m;
		return matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
	}

	let mode = $state<Theme>(getStored());

	$effect(() => {
		const r = resolved(mode);
		document.documentElement.classList.toggle('dark', r === 'dark');
		document.documentElement.style.colorScheme = r;
		try {
			if (mode === 'system') localStorage.removeItem(KEY); else localStorage.setItem(KEY, mode);
		} catch {}
	});

	$effect(() => {
		function onStorage(e: StorageEvent) { if (e.key === KEY) { mode = getStored(); } }
		function onScheme() { if (mode === 'system') mode = 'system'; }
		window.addEventListener('storage', onStorage);
		const mq = matchMedia('(prefers-color-scheme: dark)');
		mq.addEventListener('change', onScheme);
		return () => { window.removeEventListener('storage', onStorage); mq.removeEventListener('change', onScheme); };
	});

	function cycle() { mode = mode === 'system' ? 'light' : mode === 'light' ? 'dark' : 'system'; }
</script>

<button type="button" onclick={cycle} aria-label="Theme: {mode}" class="inline-flex size-8 items-center justify-center rounded-md text-muted-foreground hover:bg-accent hover:text-foreground transition-colors">
	{#if mode === 'light'}<Sun class="size-4" />
	{:else if mode === 'dark'}<Moon class="size-4" />
	{:else}<Monitor class="size-4" />
	{/if}
</button>

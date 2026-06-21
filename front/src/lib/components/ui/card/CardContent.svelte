<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import { cn } from '$lib/utils';

	type Props = HTMLAttributes<HTMLDivElement> & {
		children?: Snippet;
		class?: string;
	};

	let { class: className, children, ...rest }: Props = $props();
</script>

<div class={cn('p-6 pt-0', className)} {...rest}>
	{#if children}{@render children()}{/if}
</div>

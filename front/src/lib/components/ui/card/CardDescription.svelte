<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import { cn } from '$lib/utils';

	type Props = HTMLAttributes<HTMLParagraphElement> & {
		children?: Snippet;
		class?: string;
	};

	let { class: className, children, ...rest }: Props = $props();
</script>

<p class={cn('text-sm text-muted-foreground', className)} {...rest}>
	{#if children}{@render children()}{/if}
</p>

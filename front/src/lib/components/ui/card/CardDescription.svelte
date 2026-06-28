<svelte:options
  customElement={{
    props: {
      class: { type: "String" },
      style: { type: "String" },
      id: { type: "String" }
    }
  }}
/>

<script lang="ts">
	import type { Snippet } from 'svelte';
	import type { HTMLAttributes } from 'svelte/elements';
	import { cn } from '../../../utils';

	type Props = HTMLAttributes<HTMLParagraphElement> & {
		children?: Snippet;
		class?: string;
	};

	let { class: className, children, ...rest }: Props = $props();
</script>

<p class={cn('text-sm text-muted-foreground', className)} {...rest}>
	{#if children}{@render children()}{/if}
</p>

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

	type Props = HTMLAttributes<HTMLDivElement> & {
		children?: Snippet;
		class?: string;
	};

	let { class: className, children, ...rest }: Props = $props();
</script>

<div class={cn('flex flex-col gap-1.5 p-6', className)} {...rest}>
	{#if children}{@render children()}{/if}
</div>

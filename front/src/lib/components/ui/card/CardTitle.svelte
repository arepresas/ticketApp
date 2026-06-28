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

	type Props = HTMLAttributes<HTMLHeadingElement> & {
		children?: Snippet;
		class?: string;
	};

	let { class: className, children, ...rest }: Props = $props();
</script>

<h3
	class={cn('text-lg font-semibold leading-none tracking-tight', className)}
	{...rest}
>
	{#if children}{@render children()}{/if}
</h3>

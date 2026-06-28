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

	type Props = HTMLAttributes<HTMLElement> & {
		children?: Snippet;
		class?: string;
	};

	let { class: className, children, ...rest }: Props = $props();
</script>

<div
	class={cn('rounded-xl border border-border bg-card text-card-foreground shadow-sm', className)}
	{...rest}
>
	{#if children}{@render children()}{/if}
</div>

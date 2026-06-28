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
	import type { HTMLAttributes } from 'svelte/elements';
	import { cn } from '../../../utils';

	type Props = HTMLAttributes<HTMLHRElement> & {
		class?: string;
		orientation?: 'horizontal' | 'vertical';
	};

	let {
		class: className,
		orientation = 'horizontal',
		...rest
	}: Props = $props();
</script>

<hr
	class={cn(
		'shrink-0 bg-border',
		orientation === 'horizontal' ? 'h-px w-full' : 'h-full w-px',
		className
	)}
	{...rest}
/>

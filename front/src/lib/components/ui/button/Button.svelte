<script lang="ts" module>
	import { tv, type VariantProps } from 'tailwind-variants';

	export const buttonVariants = tv({
		base: 'inline-flex items-center justify-center gap-2 whitespace-nowrap rounded-md text-sm font-medium transition-all focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 focus-visible:ring-offset-background disabled:pointer-events-none disabled:opacity-50 [&_svg]:pointer-events-none [&_svg]:size-4 [&_svg]:shrink-0',
		variants: {
			variant: {
				default: 'bg-primary text-primary-foreground shadow hover:bg-primary/90',
				destructive:
					'bg-destructive text-destructive-foreground shadow-sm hover:bg-destructive/90',
				outline:
					'border border-input bg-background shadow-sm hover:bg-accent hover:text-accent-foreground',
				secondary: 'bg-secondary text-secondary-foreground shadow-sm hover:bg-secondary/80',
				ghost: 'hover:bg-accent hover:text-accent-foreground',
				link: 'text-primary underline-offset-4 hover:underline'
			},
			size: {
				default: 'h-9 px-4 py-2',
				sm: 'h-8 rounded-md px-3 text-xs',
				lg: 'h-11 rounded-md px-6 text-base',
				xl: 'h-12 rounded-lg px-8 text-base',
				icon: 'size-9'
			}
		},
		defaultVariants: {
			variant: 'default',
			size: 'default'
		}
	});

	export type ButtonVariant = VariantProps<typeof buttonVariants>['variant'];
	export type ButtonSize = VariantProps<typeof buttonVariants>['size'];

	export type ButtonProps = (
		| ({ href: string } & HTMLAnchorAttributes)
		| ({ href?: undefined } & HTMLButtonAttributes)
	) & {
		variant?: ButtonVariant;
		size?: ButtonSize;
		class?: string;
	};
</script>

<script lang="ts">
	import type { HTMLAnchorAttributes, HTMLButtonAttributes } from 'svelte/elements';
	import { cn } from '$lib/utils';

	let {
		variant = 'default',
		size = 'default',
		class: className,
		href,
		children,
		...rest
	}: ButtonProps & { children?: unknown } = $props();
</script>

{#if href}
	<a {href} class={cn(buttonVariants({ variant, size }), className)} {...rest as HTMLAnchorAttributes}>
		{#if children}{@render children()}{/if}
	</a>
{:else}
	<button class={cn(buttonVariants({ variant, size }), className)} {...rest as HTMLButtonAttributes}>
		{#if children}{@render children()}{/if}
	</button>
{/if}

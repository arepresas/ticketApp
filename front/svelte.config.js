import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
export default {
	preprocess: vitePreprocess(),
	compilerOptions: {
		// Compile every Svelte component as a custom element.
		// Components that need a non-default props surface override via
		//   <svelte:options customElement={{ props: { ... } }} />
		// — that's how the shadcn-style UI primitives silence the
		// `custom_element_props_identifier` warning while keeping the
		// spread-rest pattern in their `$props()`.
		customElement: true
	}
};

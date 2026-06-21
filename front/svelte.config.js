import { vitePreprocess } from '@sveltejs/vite-plugin-svelte';

/** @type {import('@sveltejs/kit').Config} */
export default {
	preprocess: vitePreprocess(),
	compilerOptions: {
		// Compile every Svelte component as a custom element.
		// Use <svelte:options customElement={{ props: { ... } }} /> per-component
		// to override the inferred props API surface.
		customElement: true
	}
};

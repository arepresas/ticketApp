import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';
import { resolve } from 'node:path';

const svelteClientEntry = fileURLToPath(
	new URL('./node_modules/svelte/src/index-client.js', import.meta.url)
);

export default defineConfig(({ mode }) => ({
	plugins: [tailwindcss(), svelte()],
	resolve:
		mode === 'test' || process.env.VITEST
			? { alias: [{ find: /^svelte$/, replacement: svelteClientEntry }] }
			: undefined,
	build: {
		target: 'es2023',
		sourcemap: true,
		rollupOptions: {
			input: resolve(__dirname, 'index.html')
		}
	},
	server: {
		port: 5173,
		proxy: { '/api': 'http://localhost:8080' }
	},
	test: {
		environment: 'jsdom',
		globals: true,
		setupFiles: ['./src/test-setup.ts'],
		server: { deps: { inline: [/svelte/, /@lucide\/svelte/] } }
	}
}));

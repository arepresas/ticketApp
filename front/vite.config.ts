import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';
import { fileURLToPath } from 'node:url';

const svelteClientEntry = fileURLToPath(
  new URL('./node_modules/svelte/src/index-client.js', import.meta.url)
);

export default defineConfig({
  plugins: [svelte()],
  resolve: {
    alias: [{ find: /^svelte$/, replacement: svelteClientEntry }]
  },
  build: {
    target: 'es2023',
    sourcemap: true,
    lib: {
      entry: 'src/index.ts',
      formats: ['es'],
      fileName: 'ticketapp-front'
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080'
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.ts'],
    server: {
      deps: {
        inline: [/svelte/]
      }
    }
  }
});

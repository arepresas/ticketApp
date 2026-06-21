import '@testing-library/svelte/vitest';
import { vi } from 'vitest';

// jsdom does not implement matchMedia. Provide a minimal stub used by
// the dark-mode theme toggle on first paint and on $effect re-runs.
if (typeof window !== 'undefined' && typeof window.matchMedia !== 'function') {
	Object.defineProperty(window, 'matchMedia', {
		writable: true,
		value: vi.fn().mockImplementation((query: string) => ({
			matches: false,
			media: query,
			onchange: null,
			addListener: vi.fn(),
			removeListener: vi.fn(),
			addEventListener: vi.fn(),
			removeEventListener: vi.fn(),
			dispatchEvent: vi.fn()
		}))
	});
}

import { describe, expect, it } from 'vitest';
import { cn } from './utils';

describe('cn', () => {
	it('merges tailwind class lists', () => {
		expect(cn('px-2', 'px-4')).toBe('px-4');
	});

	it('handles conditional classes', () => {
		expect(cn('base', false && 'hidden', 'extra')).toBe('base extra');
	});
});

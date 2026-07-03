import { describe, expect, it } from 'vitest';
import {
	ACCEPTED_MIME_EXACT,
	ACCEPTED_MIME_PREFIXES,
	MAX_BYTES,
	fileSizeError,
	humanSize,
	isAcceptedFile
} from './validation';

describe('upload validation', () => {
	describe('isAcceptedFile', () => {
		it('accepts any image/* MIME', () => {
			for (const type of ['image/png', 'image/jpeg', 'image/heic', 'image/webp']) {
				expect(isAcceptedFile({ type })).toBe(true);
			}
		});

		it('accepts application/pdf', () => {
			expect(isAcceptedFile({ type: 'application/pdf' })).toBe(true);
		});

		it('rejects text, audio, video, and unknown types', () => {
			for (const type of ['text/plain', 'audio/mp3', 'video/mp4', 'application/zip', '']) {
				expect(isAcceptedFile({ type })).toBe(false);
			}
		});
	});

	describe('fileSizeError', () => {
		it('returns null when the file fits within MAX_BYTES', () => {
			expect(fileSizeError({ size: 0 })).toBeNull();
			expect(fileSizeError({ size: MAX_BYTES - 1 })).toBeNull();
			expect(fileSizeError({ size: MAX_BYTES })).toBeNull();
		});

		it('returns a too-large message for files above MAX_BYTES', () => {
			const msg = fileSizeError({ size: MAX_BYTES + 1 });
			expect(msg).not.toBeNull();
			expect(msg).toMatch(/too large/i);
		});

		it('quotes both the file size and the cap in the error message', () => {
			const msg = fileSizeError({ size: 12 * 1024 * 1024 });
			expect(msg).toMatch(/12\.0 MB/);
			expect(msg).toMatch(/10\.0 MB/);
		});
	});

	describe('humanSize', () => {
		it('formats bytes under 1 KB without decimals', () => {
			expect(humanSize(0)).toBe('0 B');
			expect(humanSize(512)).toBe('512 B');
			expect(humanSize(1023)).toBe('1023 B');
		});

		it('formats kilobytes with one decimal', () => {
			expect(humanSize(1024)).toBe('1.0 KB');
			expect(humanSize(2048)).toBe('2.0 KB');
		});

		it('formats megabytes with one decimal', () => {
			expect(humanSize(1024 * 1024)).toBe('1.0 MB');
			expect(humanSize(MAX_BYTES)).toBe('10.0 MB');
		});
	});

	it('exports a 10 MB cap and the right MIME allow-lists', () => {
		expect(MAX_BYTES).toBe(10 * 1024 * 1024);
		expect(ACCEPTED_MIME_EXACT).toEqual(['application/pdf']);
		expect(ACCEPTED_MIME_PREFIXES).toEqual(['image/']);
	});
});
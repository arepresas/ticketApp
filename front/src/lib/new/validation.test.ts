import { describe, expect, it } from 'vitest';
import {
	ACCEPTED_MIME_EXACT,
	ACCEPTED_MIME_PREFIXES,
	MAX_BYTES,
	fileSizeError,
	humanSize,
	isAcceptedFile,
	validateFiles
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

describe('validateFiles (multi-file intake)', () => {
	function makeFile(name: string, type: string, size: number): File {
		// Buffer the requested size up front so file.arrayBuffer()
		// resolves to a Uint8Array of the expected length. The
		// content is irrelevant — size + type is what validation
		// looks at.
		const bytes = new Uint8Array(size);
		return new File([bytes], name, { type });
	}

	it('returns an empty array for an empty input', async () => {
		expect(await validateFiles([])).toEqual([]);
	});

	it('accepts every file when the batch is uniformly valid', async () => {
		const decisions = await validateFiles([
			makeFile('a.png', 'image/png', 1024),
			makeFile('b.pdf', 'application/pdf', 2048),
			makeFile('c.jpg', 'image/jpeg', 4096)
		]);
		expect(decisions).toHaveLength(3);
		for (const d of decisions) {
			expect(d.status).toBe('accepted');
			if (d.status === 'accepted') {
				expect(d.bytes.byteLength).toBeGreaterThan(0);
			}
		}
	});

	it('returns one rejected decision per offending file with a reason', async () => {
		const decisions = await validateFiles([
			makeFile('ok.png', 'image/png', 1024),
			makeFile('bad.txt', 'text/plain', 100),
			makeFile('huge.png', 'image/png', MAX_BYTES + 1)
		]);
		expect(decisions).toHaveLength(3);
		const statuses = decisions.map((d) => d.status);
		expect(statuses).toEqual(['accepted', 'rejected', 'rejected']);
		const reasons = decisions
			.filter((d) => d.status === 'rejected')
			.map((d) => (d.status === 'rejected' ? d.reason : ''));
		expect(reasons[0]).toMatch(/Only images and PDFs/);
		expect(reasons[1]).toMatch(/too large/i);
	});

	it('preserves the input order in the decision list', async () => {
		const names = ['a.png', 'b.txt', 'c.png', 'd.pdf'];
		const decisions = await validateFiles([
			makeFile(names[0], 'image/png', 100),
			makeFile(names[1], 'text/plain', 100),
			makeFile(names[2], 'image/png', 100),
			makeFile(names[3], 'application/pdf', 100)
		]);
		const seen = decisions.map((d) => d.file.name);
		expect(seen).toEqual(names);
	});
});
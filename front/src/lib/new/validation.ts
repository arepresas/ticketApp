/**
 * Pure validators for the NewTicketApp upload screen.
 *
 * Extracted from NewTicketApp.svelte so the unit tests can pin the rules
 * without needing to render a Svelte custom element (which would create
 * a shadow DOM that jsdom doesn't expose to `container.querySelector`).
 */
export const MAX_BYTES = 10 * 1024 * 1024;
export const ACCEPTED_MIME_EXACT = ['application/pdf'] as const;
export const ACCEPTED_MIME_PREFIXES = ['image/'] as const;

export function isAcceptedFile(file: { type: string }): boolean {
	if ((ACCEPTED_MIME_EXACT as readonly string[]).includes(file.type)) return true;
	return ACCEPTED_MIME_PREFIXES.some((p) => file.type.startsWith(p));
}

export function humanSize(bytes: number): string {
	if (bytes < 1024) return `${bytes} B`;
	if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
	return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function fileSizeError(file: { size: number }): string | null {
	if (file.size > MAX_BYTES) {
		return `File is too large (${humanSize(file.size)}). Max is ${humanSize(MAX_BYTES)}.`;
	}
	return null;
}

/**
 * One file's fate after the multi-file intake pipeline runs
 * {@link validateFiles}. Accepted entries carry the buffered
 * {@code Uint8Array} so the upload loop can re-stream the bytes
 * without re-reading from disk; rejected entries carry a
 * human-readable reason so the row can render the per-item error
 * inline.
 */
export type FileDecision =
	| { file: File; status: 'accepted'; bytes: Uint8Array }
	| { file: File; status: 'rejected'; reason: string };

/**
 * Validate a batch of files in one pass. Returns a {@link FileDecision}
 * for every input — order preserved, so the UI can render the rows
 * in the same order the user dropped them. Buffering
 * ({@code file.arrayBuffer()}) happens once per accepted file; the
 * orchestrator then re-streams the bytes through the
 * {@code createTicket} HTTP call without re-reading the disk.
 *
 * <p>All rejections are first-class (not thrown) so the screen can
 * show "X was rejected because Y" while still uploading the rest.
 * The user gets a per-row error chip and the chance to remove the
 * offending file before resubmitting.
 */
export async function validateFiles(files: File[]): Promise<FileDecision[]> {
	const decisions: FileDecision[] = [];
	for (const file of files) {
		if (!isAcceptedFile(file)) {
			decisions.push({ file, status: 'rejected', reason: 'Only images and PDFs are accepted.' });
			continue;
		}
		const tooBig = fileSizeError(file);
		if (tooBig) {
			decisions.push({ file, status: 'rejected', reason: tooBig });
			continue;
		}
		try {
			const bytes = new Uint8Array(await file.arrayBuffer());
			decisions.push({ file, status: 'accepted', bytes });
		} catch {
			decisions.push({ file, status: 'rejected', reason: 'Could not read the file. Try again.' });
		}
	}
	return decisions;
}
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
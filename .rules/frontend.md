# Frontend rules (Svelte 5 + TypeScript)

> Load this pack when editing anything under `front/src/`.

## Stack

- Svelte 5 (runes API)
- TypeScript (strict)
- Vite 8
- Tailwind 4
- shadcn-style primitives under `front/src/lib/components/ui/`
- Vitest + `@testing-library/svelte` for tests

## Components

- One component per file. Filename = component name in PascalCase (`KpiCard.svelte`).
- Props via `$props()` with destructuring + explicit types:

  ```ts
  let { title, count = 0, children }: {
    title: string;
    count?: number;
    children?: import('svelte').Snippet;
  } = $props();
  ```

- State: `$state`. Derived: `$derived`. Side effects on DOM: `$effect`. Never use `$effect` for derived state.
- No legacy reactive statements (`$:`). No `let` + reactive `=` patterns from Svelte 4.
- Compound primitives (`Card` + `CardContent`) are allowed when the API mirrors shadcn.

## TypeScript

- `strict: true`. No `any`. Use `unknown` + narrowing.
- Public APIs in `front/src/lib/api/*.ts` export typed functions matching the BFF contract.
- No `// @ts-ignore`. `// @ts-expect-error` only with a one-line reason + issue link.
- Run `pnpm check` before committing.

## Styling

- Tailwind utility classes. Theme tokens live in `app.css` (`bg-background`, `text-foreground`, …).
- No `<style>` blocks unless styling depends on runtime values that Tailwind cannot express.
- No hardcoded colors (`#fff`, `rgb(…)`) in components.

## HTTP clients

- One client per resource under `front/src/lib/api/<resource>.ts`.
- Errors throw `HttpError { status, body }`. Don't `console.error` inside the client.
- No raw `fetch` in components — go through the typed client.

## Web components

- `front/src/index.ts` is the entry. Each registered custom element is its own bundle entry.
- Don't import the whole library from a leaf component.

## Testing

- Vitest. `pnpm test` runs once, `pnpm test:watch` for dev, `pnpm test:ci` for CI (writes junit).
- Mock HTTP via `vi.mock`. Real network calls are banned in unit tests.
- Component tests render the component; logic tests extract the pure function into `*.test.ts`.
- No `await sleep(...)` in tests. Use `waitFor` or polling helpers.
- Coverage: 70% lines / 60% branches on changed files. Enforced by SonarQube, not by the test runner.

## What to avoid

- `$effect` for derived state.
- Inline styles for values Tailwind can express.
- Mixing `console.log` and structured logging.
- Importing from `front/src/lib/components/ui/` deep paths (`badge/Badge.svelte` is OK; `badge/../button/Button.svelte` is not).
- Hardcoded English error strings — go through the i18n module.

## Quick checklist before pushing

- [ ] `pnpm install --frozen-lockfile --ignore-scripts`
- [ ] `pnpm test:ci` green
- [ ] `pnpm build` green
- [ ] `pnpm test:coverage` produced `front/coverage/lcov.info`
- [ ] `pnpm check` clean (types + a11y)
- [ ] No new dependencies without PR-body justification

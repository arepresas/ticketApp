/**
 * Commitlint — Conventional Commits per CONVENTIONS.md §11.1.
 *
 * Enforced in CI (.github/workflows/ci.yml, `commitlint` job).
 * Local check: `pnpm exec commitlint --from origin/main --to HEAD`
 */
module.exports = {
  extends: ['@commitlint/config-conventional'],
  rules: {
    // CONVENTIONS.md §11.1: subject ≤ 72 chars (default is 100).
    'header-max-length': [2, 'always', 72],
    // Body lines wrap at 100 cols per §11.1.
    'body-max-line-length': [2, 'always', 100],
    'footer-max-line-length': [2, 'always', 100],
  },
};

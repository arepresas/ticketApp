# Pull Request

## Summary

<!-- One or two sentences describing the change. -->

## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that would cause existing functionality to change)
- [ ] Refactor / chore (no functional change)
- [ ] Documentation

## Modules touched

- [ ] `domain`
- [ ] `persistence`
- [ ] `minimax-ai` (or other AI provider module)
- [ ] `bff`
- [ ] `front`
- [ ] `local-environment`
- [ ] `.github` / CI

## Changes

<!-- Bullet list of the most important changes. -->

-

## How to test

<!-- Steps a reviewer can follow to verify the change locally. -->

1.
2.
3.

## Database / migrations

- [ ] No DB change
- [ ] New migration(s) added under `persistence/src/main/resources/db/changelog/changes/`
- [ ] Backward-compatible (additive only)
- [ ] Requires data backfill

## Quality gates

- [ ] `mvn -B verify` passes locally
- [ ] `cd front && npm test && npm run build` passes locally
- [ ] SonarQube quality gate not worsened
- [ ] New tests cover the change
- [ ] No new Sonar smells / security hotspots introduced

## Risks & rollback

<!-- Describe any risk and how to revert. -->

## Checklist

- [ ] I have read the [CONTRIBUTING](../CONTRIBUTING.md) guidelines (if available)
- [ ] My code follows the project's style
- [ ] I have commented my code, particularly in hard-to-understand areas
- [ ] I have updated relevant documentation
- [ ] My changes generate no new warnings
- [ ] I have added tests that prove my fix/feature works

# `.rules/` — reusable rule packs

This directory holds modular rule packs that an automated assistant can load on demand.

The packs are **tool-agnostic**: they are plain Markdown, addressed by file path, and contain no configuration syntax tied to any specific assistant or IDE. Point your tool at the files you want it to read.

## Loading protocol

When a task touches multiple areas, load each rule pack at the start and re-read after 30+ minutes of edits. Don't assume — the rules are short and load fast.

## When to load what

| File | Load when… |
|------|------------|
| [`backend.md`](backend.md) | Touching Java/Spring code under `domain/`, `infrastructure/`, or `bff/` |
| [`frontend.md`](frontend.md) | Touching anything under `front/src/` |
| [`database.md`](database.md) | Writing or reviewing Liquibase migrations, JDBC queries, schema changes |
| [`testing.md`](testing.md) | Adding tests (Testcontainers, Vitest, Spring `@SpringBootTest`) |
| [`security.md`](security.md) | Touching auth, JWT, OAuth, secrets, dependencies |

## Cross-references

- Authoritative repo workflow: [`../AGENTS.md`](../AGENTS.md)
- Coding standards: [`../CONVENTIONS.md`](../CONVENTIONS.md)
- Contribution process (humans): [`../CONTRIBUTING.md`](../CONTRIBUTING.md)

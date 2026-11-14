# Smart Todo CLI — agent rules

<!-- MANUAL ADDITIONS START -->
## Harness guardrails (mandatory)
- The specification in `specs/` and `.specify/memory/constitution.md` is authoritative. Implement tasks from `tasks.md` only.
- NEVER modify, delete or rename anything in `.harness/`, `evidence/runs/`, `evidence/feedback/` or `evidence/initial-generation/`.
- NEVER weaken acceptance criteria, delete/skip tests, lower assertions, or add suppressions to make a gate pass.
  The harness rejects any `@Disabled`, `@Ignore`, `Assumptions.assume*`, `@SuppressWarnings(...)`,
  `@SuppressFBWarnings` or `CHECKSTYLE:OFF` in `src/` - fix the underlying code instead.
- Do not add Checkstyle/SpotBugs/JaCoCo configuration to `pom.xml`; the external harness owns those gates.
- When you receive a harness feedback report, fix the implementation (or ask the human to revise the spec if the
  requirement itself is wrong). Do not edit the harness.
- No secrets, tokens or credentials anywhere in the repository.
<!-- MANUAL ADDITIONS END -->

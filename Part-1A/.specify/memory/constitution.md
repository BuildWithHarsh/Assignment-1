<!--
SYNC IMPACT REPORT (temporary scratch material for human review of this amendment;
remove this comment before committing the amended constitution)

Version change: TEMPLATE (unversioned placeholders) -> 1.0.0
Bump rationale: MAJOR. First concrete ratification; every placeholder principle is defined
for the first time, establishing binding governance where none previously existed.

Modified principles (placeholder -> concrete):
- [PRINCIPLE_1_NAME] -> I. Spec Authority (NON-NEGOTIABLE)
- [PRINCIPLE_2_NAME] -> II. Technology Constraints
- [PRINCIPLE_3_NAME] -> III. Quality Gates (NON-NEGOTIABLE)
- [PRINCIPLE_4_NAME] -> IV. Determinism
- [PRINCIPLE_5_NAME] -> V. Safety & Data Integrity

Added sections:
- Core Principles -> VI. Simplicity (sixth principle; the source input defines six, so one
  principle slot was added beyond the template's five)
- Additional Constraints (from [SECTION_2_NAME])
- Development Workflow (from [SECTION_3_NAME])

Removed sections: none. All template headings retained at their original levels.

Follow-up TODOs: none. No placeholder tokens deferred.
-->

# Smart Todo CLI Constitution

## Core Principles

### I. Spec Authority (NON-NEGOTIABLE)

Implementation MUST follow `specs/`, `plan.md` and `tasks.md` only. Acceptance criteria
(`AC-xx`) are binding and MUST be satisfied as written.

The coding agent MUST NOT:

- Weaken, reinterpret or narrow an acceptance criterion.
- Delete, skip or disable a failing test, or lower an assertion.
- Edit anything under `.harness/`, `evidence/runs/`, `evidence/feedback/` or
  `evidence/initial-generation/`.
- Introduce suppression annotations or comments in `src/`: `@SuppressWarnings(...)`,
  `@SuppressFBWarnings`, `CHECKSTYLE:OFF`, `@Disabled`, `@Ignore` or `Assumptions.assume*`.

Any requirement change MUST go through the spec first. When a requirement is itself wrong, work
stops and the human revises the spec; the agent MUST NOT resolve the conflict by editing the
harness or the criteria.

Rationale: the harness is the only impartial judge of done. An agent that can move the goalposts
produces green runs that prove nothing.

### II. Technology Constraints

- Java 17+, Maven, a single module.
- Runtime dependencies are limited to Jackson (`jackson-databind`, `jackson-datatype-jsr310`).
- Test dependencies are JUnit 5 only.
- The build MUST produce an executable fat jar at `target/todo-cli.jar` via
  `maven-shade-plugin` with `finalName` `todo-cli`.

Any dependency outside this list is a violation, not a judgement call.

Rationale: a fixed, minimal dependency set keeps the artifact reproducible and keeps the
security surface small enough to reason about.

### III. Quality Gates (NON-NEGOTIABLE)

The external harness owns and enforces these gates. The agent MUST NOT add Checkstyle, SpotBugs
or JaCoCo configuration to `pom.xml`, and MUST NOT lower a threshold.

| Gate | Requirement |
| --- | --- |
| Checkstyle (`.harness/lint/checkstyle.xml`) | 0 violations at severity `warning` or above |
| SpotBugs + FindSecBugs (effort `Max`, threshold `Low`) | 0 findings |
| JUnit unit tests | 100% passing, 0 skipped |
| JaCoCo line coverage (main code) | at least 80% |
| Black-box CLI contract tests (derived from the ACs) | 100% passing |

Gates run in order: integrity, build, lint, sast, unit, contract. A red gate MUST be fixed in the
implementation.

Rationale: thresholds owned by the thing being measured are not thresholds.

### IV. Determinism

An identical command sequence against an identical data file MUST produce identical stdout and an
identical exit code, with `createdAt` timestamps the only permitted variation.

Forbidden: randomness, UUIDs, locale-dependent formatting, iteration order that depends on hash
layout, and any dependence on wall-clock time beyond `createdAt`.

Rationale: black-box contract tests can only assert on output that does not move.

### V. Safety & Data Integrity

- No secrets, tokens or credentials anywhere in the repository, in code or in logs.
- User input MUST NEVER be executed, shelled out to, or interpreted as code.
- File writes MUST be atomic: write a temporary file in the same directory, then atomically move
  it over the target.
- A corrupt data file MUST NEVER be overwritten. Such a run exits `5` (`STORAGE_ERROR`) and
  leaves the file byte-for-byte unchanged.

Rationale: the data file is the user's only copy. A partial write loses it; an overwrite of
unparseable content destroys the evidence needed to recover it.

### VI. Simplicity

- No frameworks (no Spring), no database, no network access.
- Classes MUST be small and single-purpose, separated into four layers: `domain`, `storage`,
  `service`, `cli`.
- Dependencies point inward: `cli` to `service` to `storage` to `domain`. `domain` MUST NOT
  depend on any outer layer.

Rationale: the problem is small. Structure beyond the problem's size is cost with no return, and
it is what pushes line coverage out of reach.

## Additional Constraints

**Harness-owned configuration.** Tool versions are pinned by the harness and MUST NOT be
duplicated or overridden in `pom.xml`: `maven-checkstyle-plugin:3.6.0`,
`spotbugs-maven-plugin:4.8.6.6`, `findsecbugs-plugin:1.13.0`, `jacoco-maven-plugin:0.8.12`.

**Forbidden in `pom.xml`.** `checkstyle`, `spotbugs`, `jacoco`, `testFailureIgnore`,
`<skipTests>true`, `<skip>true`, `maven.test.skip`.

**Locked files.** `.harness/**`, `CLAUDE.md` and `.claude/settings.json` are hash-locked in
`.harness/lock.json`. The integrity gate fails on any drift. Relocking is a human action with a
stated reason, never an agent workaround for a failing gate.

**CLI contract.** `java -jar target/todo-cli.jar --data <path-to-json> <command> [options]`.
Success prints exactly one JSON document on stdout and exits `0`. An error prints nothing on
stdout and one JSON document on stderr: `{"error":{"code":"<CODE>","message":"<text>"}}`.
Exit codes: `0` OK, `2` VALIDATION_ERROR, `3` INVALID_TRANSITION, `4` NOT_FOUND,
`5` STORAGE_ERROR. Main class: `com.atria.todo.cli.Main`.

## Development Workflow

1. Specification flows through Spec Kit in order: `/speckit.constitution`, `/speckit.specify`,
   `/speckit.clarify`, `/speckit.plan`, `/speckit.tasks`, `/speckit.analyze`,
   `/speckit.implement`. Each generated artifact is reviewed by the human before the next step.
2. A clarification that changes an AC MUST be reflected in the spec, in
   `.harness/contract_tests.py`, and in a relock
   (`python .harness/run.py lock --reason "..."`), with a note in `harness-log.md`. This is a
   human action.
3. Immediately after `/speckit.implement` and **before** the first harness run: commit, tag
   `initial-generation`, and `python .harness/run.py snapshot initial`.
4. Repair loop: `python .harness/run.py run --label run-NN`. On failure, the newest
   `evidence/feedback/*.md` report is the input for the next fix. Labels increment; evidence is
   never edited or deleted. When all gates pass: `python .harness/run.py snapshot final`.
5. Implementation work MUST come from `tasks.md`. Work not traceable to a task does not belong
   in the repository.

## Governance

This constitution supersedes all other development practices for this project. Where a tool
default, a convention or an agent habit conflicts with it, this document wins.

**Authority.** Principles I and III are non-negotiable and MUST NOT be amended to make a failing
gate pass. The harness enforces compliance mechanically; a passing harness run is necessary but
not sufficient evidence of compliance, since principles IV, V and VI also bind.

**Amendment procedure.** Amendments are a human action. Each requires a stated rationale in the
amendment commit, a version bump under the policy below, and a migration note when existing
code, specs or contract tests must change to comply. An agent MAY propose an amendment; it MUST
NOT enact one.

**Versioning policy.** Semantic versioning:

- MAJOR: a principle is removed or redefined in a backward-incompatible way.
- MINOR: a principle or section is added, or guidance is materially expanded.
- PATCH: clarification, wording or typo fixes with no change in meaning.

**Compliance review.** Every change is checked against this constitution before it is considered
done: all six harness gates green, no forbidden pattern in `src/` or `pom.xml`, no modification
to locked or evidence paths. Complexity MUST be justified against Principle VI. `CLAUDE.md` is
the runtime guidance file for agents and MUST stay consistent with this document; it is
hash-locked, so a divergence is resolved by amending this constitution and relocking, not by
silent edits.

**Version**: 1.0.0 | **Ratified**: 2026-11-14 | **Last Amended**: 2026-11-14

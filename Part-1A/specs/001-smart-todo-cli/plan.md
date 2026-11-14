# Implementation Plan: Smart Todo CLI

**Branch**: `001-smart-todo-cli` | **Date**: 2026-11-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-smart-todo-cli/spec.md`

## Summary

A single-module Java 17 command-line task manager storing tasks in one JSON file. Four inward-pointing
layers — `domain`, `storage`, `service`, `cli` — with Jackson for JSON and JUnit 5 for tests, packaged
as an executable fat jar at `target/todo-cli.jar`.

The design is driven less by the feature's difficulty (which is low) than by the six harness gates that
judge it. Three decisions follow from the gates rather than from the requirements, and they shape every
class:

1. **`System.exit` is confined to `Main`.** All command logic lives in a `CliRunner` that *returns* an
   exit code and writes to injected streams, because a `System.exit` inside testable code would kill the
   JUnit JVM and make the 80% coverage gate unreachable.
2. **Nothing reaches stdout or stderr except through the two injected streams**, so the exact
   stdout/stderr contract (AC-16, FR-002, FR-003) is assertable in unit tests, not only through the
   black-box suite.
3. **Jackson's defaults are wrong for this spec in two specific ways** — it writes `Instant` as a
   decimal number, not an ISO string, and POJO field order is not contractually stable. Both are
   corrected explicitly rather than relied upon. See [research.md](./research.md) R1 and R2.

## Technical Context

**Language/Version**: Java 17 (`maven.compiler.release=17`). Local JDK is Temurin 21.0.12.1; the release
flag pins bytecode and API surface to 17 so the build does not silently depend on a newer JDK.

**Primary Dependencies**: Runtime — `jackson-databind` 2.17.1 and `jackson-datatype-jsr310` 2.17.1, and
nothing else (Constitution II). Build — `maven-compiler-plugin` 3.14.1, `maven-surefire-plugin` 3.5.6,
`maven-shade-plugin` 3.6.0 (`finalName` `todo-cli`). Every one of these, with transitives, is already in
the local Maven repository, so the build does not require network access.

**Storage**: One JSON file per store, path supplied by `--data`. Shape is a single JSON object carrying
the task list and the id counter (FR-029a). Writes are atomic: temp file in the same directory, then
`Files.move` with `ATOMIC_MOVE` + `REPLACE_EXISTING`.

**Testing**: JUnit 5 (`junit-jupiter` 5.12.2) only, run by Surefire. Unit tests cover all four layers;
`CliRunner` is driven directly with injected streams and a temp-directory data file. The black-box
contract suite in `.harness/contract_tests.py` is the external oracle and is never edited.

**Target Platform**: Any JRE 17+ on Windows, Linux or macOS. Primary development and gate execution is
Windows 11 / NTFS, which is the platform that makes `ATOMIC_MOVE` worth verifying early (research R4).

**Project Type**: Single-module Maven CLI application. No web, no service, no UI.

**Performance Goals**: Any single command against a store of 1,000 tasks completes in under 2 seconds
(SC-006). The whole store is read into memory and rewritten per mutating command; at this scale that is
comfortably inside the budget and is the simplest correct design (Constitution VI).

**Constraints**: No frameworks, no database, no network, no randomness, no locale-dependent formatting
(Constitution IV and VI). Checkstyle ceilings apply to main sources and bind the design directly: file
≤ 400 lines, line ≤ 120 chars, method ≤ 60 lines, ≤ 6 parameters, cyclomatic complexity ≤ 12, nested
`if` depth ≤ 3, ≤ 6 `return` statements per method. SpotBugs runs at effort `Max`, threshold `Low`, with
FindSecBugs, and **no suppression of any kind is permitted** — so patterns that trip it must be designed
out, not annotated away (research R3).

**Scale/Scope**: One local user, one store file, no concurrency, no locking. 6 commands, 6 task fields,
4 exit codes beyond success, 42 functional requirements, 16 acceptance criteria.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Requirement | Plan compliance |
|---|---|---|
| I. Spec Authority | Build only what `specs/` and `tasks.md` define; never weaken an AC; never touch `.harness/` or `evidence/` | **PASS.** This plan implements FR-001..FR-037 and the five recorded clarifications, nothing more. No harness or evidence path is written by any task. No suppression annotation appears in any planned source file. |
| II. Technology | Java 17+, Maven, single module, runtime deps limited to Jackson databind + jsr310, tests JUnit 5 only, fat jar at `target/todo-cli.jar` via shade with `finalName todo-cli` | **PASS.** Exactly those two runtime dependencies at 2.17.1; `junit-jupiter` 5.12.2 at test scope only; shade configured with `finalName` `todo-cli`. No Spring, no logging framework, no CLI-parsing library. |
| III. Quality Gates | Harness owns Checkstyle/SpotBugs/JaCoCo; agent adds none of it to `pom.xml`, lowers no threshold | **PASS.** `pom.xml` contains only compiler, surefire and shade plugins. Design accommodates the ceilings (see Constraints) rather than relaxing them. Coverage reachable because no logic sits behind `System.exit`. |
| IV. Determinism | Identical commands on identical data give identical stdout, stderr and exit code, `createdAt` excepted; no randomness, UUIDs or locale-dependent formatting | **PASS.** Explicit JSON field order; explicit UTF-8 on every read and write; `Locale.ROOT` on every case conversion; fixed error wording with no machine-specific content (FR-003a); tags sorted by natural `String` order; no `Collections.shuffle`, no `UUID`, no hash-order iteration in output paths. |
| V. Safety & Data Integrity | No secrets; never execute user input; atomic writes; never overwrite a corrupt file | **PASS.** `--data` is only ever opened as a file — never passed to a process, loader or evaluator. Write is temp-in-same-directory then atomic move. Any unreadable, malformed, zero-byte or wrong-shaped store aborts with exit 5 *before* any write is attempted. No path, file content or system error text appears in output (FR-037). |
| VI. Simplicity | No frameworks, no DB, no network; small single-purpose classes; `cli → service → storage → domain` | **PASS.** 14 main classes, each one job, largest projected well under the 400-line file ceiling. Dependency direction enforced by construction and asserted by a unit test over package imports. `domain` depends on nothing but the JDK — in particular, not on Jackson. |

**Result: all six principles pass. No violations to justify, so Complexity Tracking below is empty.**

Two points worth stating plainly, because they are where a plausible implementation would drift:

- **`domain` stays Jackson-free.** Serialization annotations on `Task` would be the shortest path, but it
  would invert the dependency rule in Principle VI. Mapping happens in `storage`, which is the layer that
  owns the format.
- **Agreement between the two JSON surfaces is structural, not coincidental.** The task object printed to
  stdout and the task object inside the store file have the same six fields in the same order because
  both go through one `TaskJson` mapper, not because two code paths were written to match.

## Project Structure

### Documentation (this feature)

```text
specs/001-smart-todo-cli/
├── plan.md                  # This file (/speckit-plan output)
├── spec.md                  # Feature specification, incl. 5 recorded clarifications
├── research.md              # Phase 0 output: 12 decisions, gate-risk analysis
├── data-model.md            # Phase 1 output: entities, invariants, state machine
├── quickstart.md            # Phase 1 output: build, run, verify
├── contracts/
│   ├── cli-contract.md      # Command surface, exit codes, error documents
│   └── store-schema.md      # On-disk store shape and strict-validation rules
├── checklists/
│   └── requirements.md      # Spec quality checklist (16/16)
└── tasks.md                 # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

```text
pom.xml                                  # Java 17, Jackson, JUnit 5, shade -> target/todo-cli.jar

src/main/java/com/atria/todo/
├── domain/
│   ├── Task.java                        # Immutable task; defensive copy of tags
│   ├── Priority.java                    # LOW | MEDIUM | HIGH, with sort rank
│   ├── Status.java                      # TODO | IN_PROGRESS | DONE
│   ├── Transitions.java                 # The 4 legal status moves; nothing else
│   ├── TaskValidator.java               # Title trim/length, tag normalisation
│   ├── ErrorCode.java                   # VALIDATION_ERROR=2 .. STORAGE_ERROR=5
│   └── TodoException.java               # Carries an ErrorCode + fixed message
├── storage/
│   ├── TaskStore.java                   # In-memory store: tasks + lastIssuedId
│   ├── TaskJson.java                    # Task <-> ordered JSON node mapping
│   ├── StoreCodec.java                  # Parse + strict shape validation
│   └── JsonTaskRepository.java          # Load / save; atomic write
├── service/
│   └── TaskService.java                 # add, get, list, update, delete, move
└── cli/
    ├── Main.java                        # Thin: builds CliRunner, calls System.exit
    ├── CliRunner.java                    # Returns exit code; writes injected streams
    ├── ArgParser.java                   # Tokenise; reject unknown/repeated/valueless
    ├── ParsedCommand.java               # Command name + options + positionals
    └── JsonWriter.java                  # Single configured ObjectMapper; output

src/test/java/com/atria/todo/
├── domain/                              # Validation, transitions, tag normalisation
├── storage/                             # Round-trip, strict-shape rejection, atomicity
├── service/                             # Filtering, sorting, id-counter behaviour
├── cli/                                 # Exit codes, stream contract, arg faults
└── architecture/
    └── LayerDependencyTest.java         # Asserts cli -> service -> storage -> domain
```

**Structure Decision**: Single Maven module with the four packages named in the feature input, under
`com.atria.todo`, and `com.atria.todo.cli.Main` as the entry point. The layer split is the one the
constitution mandates, so it is not re-litigated here; the only structural judgement added is the split
of `Main` from `CliRunner` (for gate reachability, see Summary) and the placement of `TaskJson` in
`storage` so that one mapper serves both the store file and stdout.

### Phase sequencing

The build order below is chosen so that the cheapest signal arrives first. Task generation
(`/speckit-tasks`) should preserve it.

1. **Gate probe first.** `pom.xml` plus a trivial `Main` that prints `[]`, then run the harness. This
   surfaces how Checkstyle and especially SpotBugs/FindSecBugs behave on *this* toolchain before any real
   code exists — most importantly whether opening a user-supplied `--data` path raises a FindSecBugs path
   -traversal finding, which cannot be suppressed and would otherwise be discovered after the whole
   storage layer is written (research R3, R4).
2. **`domain`** — pure, no I/O, trivially testable, and the source of most coverage.
3. **`storage`** — codec and repository, including strict-shape rejection and atomic write.
4. **`service`** — orchestration over domain + storage.
5. **`cli`** — parsing, dispatch, exit-code mapping, output.
6. **Full gate run**, then the repair loop against `evidence/feedback/*.md`.

## Complexity Tracking

> No Constitution Check violations. Nothing to justify.

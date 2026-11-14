---

description: "Task list for Smart Todo CLI implementation"
---

# Tasks: Smart Todo CLI

**Input**: Design documents from `/specs/001-smart-todo-cli/`

**Prerequisites**: [plan.md](./plan.md), [spec.md](./spec.md), [research.md](./research.md), [data-model.md](./data-model.md), [contracts/](./contracts/)

**Tests**: Test tasks are **included and mandatory**. Constitution III requires all JUnit tests passing
with JaCoCo line coverage ≥ 80%, so tests are part of the specification, not an option.

**Organization**: Tasks are grouped by user story so each story can be implemented and verified on its own.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete work)
- **[Story]**: Which user story the task serves (US1..US5)
- Exact file paths are given in every task

## Path Conventions

Single Maven module at the repository root: `pom.xml`, `src/main/java/`, `src/test/java/`.
Base package `com.atria.todo`. Entry point `com.atria.todo.cli.Main`.

## Harness guardrails (apply to every task)

- **Never** modify `.harness/`, `evidence/runs/`, `evidence/feedback/`, `evidence/initial-generation/`,
  `CLAUDE.md` or `.claude/settings.json` — all are hash-locked and the integrity gate fails on drift.
- **Never** add Checkstyle, SpotBugs or JaCoCo configuration to `pom.xml`; the harness owns those gates.
- **Never** add `@Disabled`, `@Ignore`, `Assumptions.assume*`, `@SuppressWarnings(...)`,
  `@SuppressFBWarnings` or `CHECKSTYLE:OFF` anywhere in `src/` — the harness greps for them and fails.
- Checkstyle ceilings bind every main source file: file ≤ 400 lines, line ≤ 120 chars, method ≤ 60 lines,
  ≤ 6 parameters, cyclomatic complexity ≤ 12, nested `if` depth ≤ 3, ≤ 6 returns per method. Every
  `switch` needs a `default`. A static-only class must be `final` with a private constructor.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Buildable skeleton, plus an early read on how the lint and SAST gates behave on this toolchain.

- [X] T001 Create the source tree: `src/main/java/com/atria/todo/domain/`, `src/main/java/com/atria/todo/storage/`, `src/main/java/com/atria/todo/service/`, `src/main/java/com/atria/todo/cli/`, and `src/test/java/com/atria/todo/{domain,storage,service,cli,architecture}/`
- [X] T002 Create `pom.xml` at the repository root: packaging `jar`, `maven.compiler.release` `17`, `project.build.sourceEncoding` `UTF-8`; runtime dependencies `com.fasterxml.jackson.core:jackson-databind:2.17.1` and `com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.17.1`; test dependency `org.junit.jupiter:junit-jupiter:5.12.2` scope `test`; plugins `maven-compiler-plugin:3.14.1`, `maven-surefire-plugin:3.5.6`, `maven-shade-plugin:3.6.0` with `finalName` `todo-cli` and `Main-Class` `com.atria.todo.cli.Main`. Add no other dependency and no Checkstyle/SpotBugs/JaCoCo configuration
- [X] T003 [P] Create `.gitignore` at the repository root ignoring `target/` and `data/`
- [X] T004 Create a probe entry point in `src/main/java/com/atria/todo/cli/Main.java` that reads the `--data` option, opens that path as a file if it exists, prints `[]` to stdout and exits `0` — just enough real file access for the SAST gate to have something to analyse
- [X] T005 Run `mvn -B clean package` and confirm the fat jar lands at `target/todo-cli.jar`
- [X] T006 Run the gate probe: `python .harness/run.py run --label probe-01` from the repository root. Record which lint/SAST findings actually appear — above all whether FindSecBugs reports `PATH_TRAVERSAL_IN` for the `--data` path, and whether `ATOMIC_MOVE` is usable on this filesystem — by appending the observed results to the residual-risk table in `specs/001-smart-todo-cli/research.md`. Do not edit anything under `.harness/` or `evidence/`

**Checkpoint**: Jar builds; the real cost of the lint and SAST gates is known before any logic is written.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The shared pipeline every command runs through — domain types, the store, argument parsing,
output and exit-code mapping. Every user story depends on this.

**CRITICAL**: No user story work can begin until this phase is complete.

### Domain core

- [X] T007 [P] Create `src/main/java/com/atria/todo/domain/Priority.java`: enum `LOW`, `MEDIUM`, `HIGH`, each with an **explicit** sort rank — `HIGH` 0, `MEDIUM` 1, `LOW` 2 — declared as a field, not derived from `ordinal()`. Add a `parse(String)` that upper-cases with `Locale.ROOT` and throws `TodoException(VALIDATION_ERROR)` on any unrecognised value, never falling back to a default
- [X] T008 [P] Create `src/main/java/com/atria/todo/domain/Status.java`: enum `TODO`, `IN_PROGRESS`, `DONE` with a `parse(String)` that upper-cases with `Locale.ROOT` and throws `TodoException(VALIDATION_ERROR)` on an unrecognised value
- [X] T009 [P] Create `src/main/java/com/atria/todo/domain/ErrorCode.java`: enum pairing each code name with its exit status — `VALIDATION_ERROR` 2, `INVALID_TRANSITION` 3, `NOT_FOUND` 4, `STORAGE_ERROR` 5. This enum is the single source of the code-to-exit mapping
- [X] T010 Create `src/main/java/com/atria/todo/domain/TodoException.java`: `extends RuntimeException`, carrying one `ErrorCode` and a message. Message text must be fixed wording per failure, optionally suffixed with the user's offending value, and must contain no file path, system error text, stack trace or timestamp (FR-003a, FR-037). Depends on T009
- [X] T011 Create `src/main/java/com/atria/todo/domain/Task.java`: `final class` with private final fields `int id`, `String title`, `Priority priority`, `Status status`, `List<String> tags`, `Instant createdAt`. The constructor must copy the incoming tags via `List.copyOf(...)` and `tags()` must return an immutable copy — both required to avoid SpotBugs `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` at threshold `Low`. Add `withTitle`, `withPriority`, `withTags`, `withStatus` returning new instances; no setters. **No Jackson annotations** — `domain` must not depend on the serialisation library. Depends on T007, T008
- [X] T012 Create `src/main/java/com/atria/todo/domain/TaskValidator.java` as a `final` class with a private constructor. `validateTitle`: trim, reject empty/blank after trim, reject length > 200 **after trim** (exactly 200 is valid, 201 is not), return the trimmed value. `normaliseTags`: split the value on `,`, trim each element, lower-case each with `Locale.ROOT`, drop empty elements, de-duplicate, sort ascending by natural `String` order — a value collapsing to nothing yields `[]` and is **not** an error. Both throw `TodoException(VALIDATION_ERROR)` on failure. Depends on T010
- [X] T013 [P] Create `src/test/java/com/atria/todo/domain/TaskValidatorTest.java`: title trimmed (`"   Pay rent  "` → `"Pay rent"`), blank rejected, 200 chars accepted, 201 rejected, and tag normalisation of `" Work,home,WORK,, urgent "` → `["home","urgent","work"]`, plus `" , ,"` → `[]`
- [X] T014 [P] Create `src/test/java/com/atria/todo/domain/PriorityTest.java`: `"high"` parses to `HIGH`, output is upper-case, `"urgent"` throws `VALIDATION_ERROR`, and the rank order is `HIGH` < `MEDIUM` < `LOW`
- [X] T015 [P] Create `src/test/java/com/atria/todo/domain/StatusTest.java`: case-insensitive parsing of all three constants and `VALIDATION_ERROR` for `"BLOCKED"` and `"FINISHED"`

### Storage

- [X] T016 Create `src/main/java/com/atria/todo/storage/TaskStore.java`: holds `List<Task> tasks` kept in id-ascending order and `int lastIssuedId` (≥ 0). `nextId()` returns `++lastIssuedId`. The counter must never be recomputed from the tasks present, and `delete` must leave it unchanged (FR-029a). Depends on T011
- [X] T017 Create `src/main/java/com/atria/todo/storage/TaskJson.java`: maps `Task` to an **ordered** `LinkedHashMap` emitting exactly `id`, `title`, `priority`, `status`, `tags`, `createdAt` in that order, and back again. `createdAt` is rendered as an ISO-8601 UTC string ending in `Z`. This one mapper serves both the store file and stdout so the two surfaces cannot drift. Depends on T011
- [X] T018 Create `src/main/java/com/atria/todo/storage/StoreCodec.java`: owns the single `ObjectMapper`, configured to register `JavaTimeModule`, **disable** `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS` (without this `createdAt` serialises as a decimal number and AC-01 fails), and enable `FAIL_ON_UNKNOWN_PROPERTIES`. Parses store JSON into `TaskStore` and serialises it back. Shape validation is added later in T044. Depends on T016, T017
- [X] T019 Create `src/main/java/com/atria/todo/storage/JsonTaskRepository.java`: `load(Path)` returns an empty store when the file does **not** exist, **without creating it** (FR-030); `save(Path, TaskStore)` writes to a temporary file created **in the same directory** as the target, with explicit `StandardCharsets.UTF_8`, then `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`; on any `IOException` it deletes the temp file, raises `TodoException(STORAGE_ERROR)` and leaves the target untouched. Same-directory is required — a temp file on another volume cannot move atomically. Depends on T018
- [X] T020 [P] Create `src/test/java/com/atria/todo/storage/JsonTaskRepositoryTest.java` using `@TempDir`: a missing file loads as an empty store and is not created on disk; a save-then-load round trip preserves all six fields; after a save no `*.tmp` file is left behind in the directory

### CLI plumbing

- [X] T021 [P] Create `src/main/java/com/atria/todo/cli/ParsedCommand.java`: carries the command name, a `Map<String,String>` of options and a `List<String>` of positional arguments
- [X] T022 Create `src/main/java/com/atria/todo/cli/ArgParser.java`: reads the global `--data` option before the command name and raises `VALIDATION_ERROR` when it is absent; raises `VALIDATION_ERROR` for an unknown option name, a **repeated** option (`--title a --title b`), an option present without a value, an unknown command, and an unexpected positional argument. Provides integer id parsing that raises `VALIDATION_ERROR` on a non-integer. Option names match case-sensitively; enum option values are parsed case-insensitively. Depends on T010, T021
- [X] T023 [P] Create `src/test/java/com/atria/todo/cli/ArgParserTest.java`: missing `--data`, unknown command `frobnicate`, unknown option `--colour red`, repeated `--title`, option without a value, extra positional, and non-integer id — each expecting `VALIDATION_ERROR`
- [X] T024 Create `src/main/java/com/atria/todo/cli/JsonWriter.java`: writes exactly one JSON document to an injected stream, and writes the error document `{"error":{"code":"<CODE>","message":"<text>"}}`. Uses the mapper from `StoreCodec`; writes with explicit UTF-8. Depends on T018
- [X] T025 Create `src/main/java/com/atria/todo/cli/CliRunner.java`: constructor takes the output and error streams (never referencing `System.out`/`System.err` itself) and `run(String[] args)` **returns** an `int` exit code. Dispatches on the command name via a `switch` with an explicit `default`, delegating to one short handler method per command. Catches `TodoException` and maps it through `ErrorCode` to the error document plus exit code; separately catches `IOException | RuntimeException` as a last resort and reports `STORAGE_ERROR` with fixed wording — a bare `catch (Exception)` risks a SpotBugs `REC_CATCH_EXCEPTION` finding and catching `Throwable`/`Error` is forbidden by Checkstyle `IllegalCatch`. On success nothing may reach the error stream; on failure nothing may reach the output stream. Depends on T022, T024
- [X] T026 Replace the probe in `src/main/java/com/atria/todo/cli/Main.java` with the real entry point: construct `CliRunner` over `System.out` and `System.err`, call `run(args)`, and pass the returned code to `System.exit`. `System.exit` must appear **nowhere else** — a call reached from test code kills the JUnit JVM and makes the coverage gate unreachable. Depends on T025
- [X] T027 Create `src/main/java/com/atria/todo/service/TaskService.java` as the orchestration seam over `JsonTaskRepository`: load, apply, save. Command methods are added per user story. Depends on T019
- [X] T028 [P] Create `src/test/java/com/atria/todo/architecture/LayerDependencyTest.java`: scan the import statements of each main source file and assert there are **no outward imports** — `domain` imports nothing from `com.atria.todo.storage`, `.service`, `.cli` or `com.fasterxml.jackson`; `storage` imports nothing from `.service` or `.cli`; `service` imports nothing from `.cli`. Skipping a layer **inward** is permitted: `cli` may import `storage` directly (as `JsonWriter` does for the configured mapper in T024) and that must not fail this test. Assert direction only, never adjacency — this is Constitution VI expressed as a test rather than a convention

**Checkpoint**: The pipeline runs end to end for an unimplemented command (exit 2 on unknown command), and
the layering rule is enforced by a test. User stories can begin.

---

## Phase 3: User Story 1 - Capture a task and get it back (Priority: P1) — MVP

**Goal**: A user records a task with one option and reads it back in a later invocation. Defaults, title and
tag normalisation, sequential never-reused ids, persistence, and a missing file reading as empty.

**Independent Test**: `add --title "Buy milk"`, then `list` and `get 1` in separate processes against the same
data file. On its own this is a working, persistent task recorder.

### Tests for User Story 1

> Write these first and confirm they fail before implementing T033–T036.

- [X] T029 [P] [US1] Create `src/test/java/com/atria/todo/cli/AddCommandTest.java`: `add --title "Buy milk"` yields id `1`, priority `MEDIUM`, status `TODO`, tags `[]` and a `createdAt` **string** ending in `Z` (AC-01); title trimming (AC-02); blank and 201-char titles rejected with exit `2`, 200 accepted; `--priority high` stored as `HIGH` and `--priority urgent` rejected (AC-03); `--tags " Work,home,WORK,, urgent "` printed as `["home","urgent","work"]` (AC-04)
- [X] T030 [P] [US1] Create `src/test/java/com/atria/todo/cli/GetCommandTest.java`: `get 1` returns the stored task; `get 99` exits `4` `NOT_FOUND`; `get abc` exits `2` `VALIDATION_ERROR` (AC-06)
- [X] T031 [P] [US1] Create `src/test/java/com/atria/todo/cli/ListAllCommandTest.java`: `list` against a missing data file prints `[]` and exits `0` without creating the file (AC-14); with four tasks it returns them in id-ascending order (AC-08)
- [X] T032 [P] [US1] Create `src/test/java/com/atria/todo/service/IdAllocationTest.java`: ids are `1,2,3`; after deleting id `3` the next `add` is `4`, never `3` (AC-05); `lastIssuedId` persists through a save/load cycle and survives emptying the task list

### Implementation for User Story 1

- [X] T033 [US1] Add `add`, `get` and `listAll` to `src/main/java/com/atria/todo/service/TaskService.java`: `add` applies `TaskValidator` to the title and tags, defaults priority to `MEDIUM` and status to `TODO`, takes its id from `TaskStore.nextId()`, and sets `createdAt` to `Instant.now().truncatedTo(ChronoUnit.SECONDS)`; `get` raises `NOT_FOUND` for an absent id; `listAll` returns tasks in id order
- [X] T034 [US1] Add the `add` handler to `src/main/java/com/atria/todo/cli/CliRunner.java`: require `--title`, accept optional `--priority` and `--tags`, print the created task
- [X] T035 [US1] Add the `get` handler to `src/main/java/com/atria/todo/cli/CliRunner.java`: exactly one positional id, print the task
- [X] T036 [US1] Add the `list` handler to `src/main/java/com/atria/todo/cli/CliRunner.java` for the no-options case: print a JSON array of all tasks
- [X] T037 [US1] Verify the story end to end: `mvn -B clean package`, then run the steps 1–4 walkthrough in `specs/001-smart-todo-cli/quickstart.md` and confirm AC-01, AC-02, AC-03, AC-04, AC-05, AC-13 and AC-14 behave as written

**Checkpoint**: A usable todo recorder. `add`, `get` and bare `list` work and persist.

---

## Phase 4: User Story 2 - Move work through its stages (Priority: P2)

**Goal**: Status advances and retreats through exactly four legal moves; everything else is refused with the
task untouched.

**Independent Test**: Walk one task `TODO → IN_PROGRESS → DONE → TODO`, then attempt a forbidden jump and
confirm the task is unchanged.

### Tests for User Story 2

- [X] T038 [P] [US2] Create `src/test/java/com/atria/todo/domain/TransitionsTest.java`: the four legal moves `TODO→IN_PROGRESS`, `IN_PROGRESS→DONE`, `IN_PROGRESS→TODO`, `DONE→TODO` are allowed; `TODO→DONE`, `DONE→IN_PROGRESS` and every same-status move (`TODO→TODO`, `IN_PROGRESS→IN_PROGRESS`, `DONE→DONE`) are refused
- [X] T039 [P] [US2] Create `src/test/java/com/atria/todo/cli/MoveCommandTest.java`: the full legal walk `IN_PROGRESS, TODO, IN_PROGRESS, DONE, TODO` all succeed; `move 1 DONE` from `TODO` exits `3` and `get 1` shows the status unchanged; `move 1 TODO` from `TODO` exits `3`; `move 1 FINISHED` exits **`2`** not `3`, because an unrecognised status word is a validation fault, not a transition fault; `move 99 DONE` exits `4` (AC-12, AC-06)

### Implementation for User Story 2

- [X] T040 [US2] Create `src/main/java/com/atria/todo/domain/Transitions.java` as a `final` class with a private constructor, holding the four legal `(from, to)` pairs as data and exposing one predicate over them. Express the table as data rather than nested `if`s to stay inside the nested-depth and cyclomatic ceilings. Depends on T008
- [X] T041 [US2] Add `move` to `src/main/java/com/atria/todo/service/TaskService.java`: resolve the id (`NOT_FOUND` when absent), consult `Transitions`, raise `INVALID_TRANSITION` when the move is illegal **leaving the stored task untouched**, otherwise persist the new status
- [X] T042 [US2] Add the `move` handler to `src/main/java/com/atria/todo/cli/CliRunner.java`: two positionals (id, status). Parse the status word **first** so an unknown word yields `VALIDATION_ERROR` (exit 2) before any transition check, then look up the id, then test legality — the order required by FR-006. Print the updated task

**Checkpoint**: US1 and US2 both work independently.

---

## Phase 5: User Story 3 - Trust the tool inside a script (Priority: P2)

**Goal**: Distinguishable success and failure without parsing prose, strict refusal of a damaged or foreign
store, and byte-identical output across runs.

**Independent Test**: Run a deliberately bad invocation and assert stdout is empty with one error document on
stderr and the right exit code; corrupt the data file and confirm it is byte-for-byte unchanged afterwards.

> **These four behaviours are invisible to the locked contract suite** — it asserts only `error.code`, never
> the message text, tests one fault at a time, and never uses a repeated option or a zero-byte file. A wrong
> implementation of T047–T050 will still pass the contract gate, so T043–T046 are the only thing standing
> behind them.

### Tests for User Story 3

- [X] T043 [P] [US3] Create `src/test/java/com/atria/todo/storage/StoreCodecValidationTest.java`, one case per row of the validation table in `specs/001-smart-todo-cli/contracts/store-schema.md`: not valid JSON; a bare array `[]` and `[{...}]`; a JSON scalar (`42`, `"x"`, `true`, `null`); missing `tasks`; missing `lastIssuedId`; `lastIssuedId` non-integer or negative; `tasks` not an array; an unknown member at root or inside a task; a task missing any of the six fields; a task field of the wrong type; an unknown `priority` or `status` constant. Every one expects `STORAGE_ERROR` with the file left byte-for-byte unchanged
- [X] T044 [P] [US3] Create `src/test/java/com/atria/todo/storage/ZeroByteStoreTest.java`: a zero-byte data file exits `5` for both `list` and `add` and is left untouched — **not** read as an empty list. Contrast with an absent file, which is a clean start (FR-031a versus FR-030)
- [X] T045 [P] [US3] Create `src/test/java/com/atria/todo/cli/ErrorContractTest.java`: for every failure class (`2`, `3`, `4`, `5`) assert the output stream is **empty** and the error stream holds exactly one parseable document whose `error.code` matches the exit code; and for every success assert the error stream is **empty**. Both directions matter — a one-sided assertion passes for an implementation that chatters on stderr
- [X] T046 [P] [US3] Create `src/test/java/com/atria/todo/cli/FaultPrecedenceTest.java`: `update 99` with no field options exits **`2`** not `4`; a malformed argument against a corrupt data file exits **`2`** not `5`; and a command failing an argument check **never opens the data file** — assert this by pointing `--data` at an unreadable or zero-byte file and still getting exit `2` (FR-006)
- [X] T047 [P] [US3] Create `src/test/java/com/atria/todo/cli/ErrorMessageTest.java`: the same failure produces the same message text on repeated runs; the message contains no file path, no system or parser error text, no stack trace and no timestamp; and where an offending value exists it appears in the message (FR-003a, FR-034, FR-037)

### Implementation for User Story 3

- [X] T048 [US3] Add strict shape validation to `src/main/java/com/atria/todo/storage/StoreCodec.java`: the root must be an object; `lastIssuedId` present and a non-negative integer; `tasks` present and an array; every task carrying all six fields of the right type with `priority` and `status` naming known constants. Missing members deserialise to `null`/`0` silently, so these presence checks — not the parser's defaults — are what enforce strictness. Any failure raises `STORAGE_ERROR` and the file is never repaired, upgraded or overwritten
- [X] T049 [US3] Handle the zero-byte file in `src/main/java/com/atria/todo/storage/StoreCodec.java`, which is the **single owner** of every content and shape fault (research R10): parsing empty content raises `STORAGE_ERROR`, whether because Jackson reports "No content" or because an explicit emptiness check catches it first. `JsonTaskRepository` keeps only the absent-versus-present decision — an **absent** file is an empty store, a present one is handed to `StoreCodec` whatever its size. Do not add a second zero-byte check in the repository
- [X] T050 [US3] Audit `src/main/java/com/atria/todo/` for the determinism and safety rules and fix any breach. Determinism (Constitution IV): `Locale.ROOT` on every `toUpperCase`/`toLowerCase`, explicit `StandardCharsets.UTF_8` on every read and write, the fixed field order from `TaskJson` on every output path, no `HashMap`/`HashSet` iteration reaching output, no randomness, no `UUID`, no default-locale formatting. Safety (FR-036, Constitution V): grep `src/main` for `ProcessBuilder`, `Runtime.exec`, `java.net.URL`, `Socket` and `HttpClient` — every one must be absent, so the "never execute user input, never touch the network" guarantee is observable rather than merely intended
- [X] T051 [US3] Confirm the write path cannot touch a damaged store: `save` is only ever reached after a successful `load`, so an exit-5 command performs no write. Verify with the corrupt-file and zero-byte checks in `specs/001-smart-todo-cli/quickstart.md`

**Checkpoint**: The tool is safe to drive from a script, and a damaged store is never overwritten.

---

## Phase 6: User Story 4 - Find the right task among many (Priority: P3)

**Goal**: Narrow a long list by tag and status, order it by importance, and combine the two.

**Independent Test**: Seed four tasks across priorities and tags, then verify each filter, the priority
ordering with its tie-break, and a combined filter-plus-sort query.

### Tests for User Story 4

- [X] T052 [P] [US4] Create `src/test/java/com/atria/todo/service/TaskQueryTest.java` seeding the four tasks from the locked suite (`t1` LOW/home, `t2` HIGH/work, `t3` MEDIUM/work+home, `t4` HIGH/home): `--tag WORK` returns ids `[2,3]` case-insensitively (AC-09); `--tag nothing` returns `[]` at exit `0`, not `NOT_FOUND`; `--status IN_PROGRESS` returns only the matching task and `--status BLOCKED` exits `2` (AC-10); `--sort priority` returns `[2,4,3,1]` — `HIGH` then `MEDIUM` then `LOW`, ties by id ascending; `--tag home --sort priority` returns `[4,3,1]`, proving filters AND together and sorting is applied after filtering (AC-11)

### Implementation for User Story 4

- [X] T053 [US4] Add tag and status filtering to `src/main/java/com/atria/todo/service/TaskService.java`: tag matching is case-insensitive against the task's already-normalised tags (lower-case the query with `Locale.ROOT`); status filtering is an exact match on the parsed enum. Filters combine with AND
- [X] T054 [US4] Add priority sorting to `src/main/java/com/atria/todo/service/TaskService.java` using the explicit rank from `Priority` (`HIGH` 0, `MEDIUM` 1, `LOW` 2) with id ascending as the tie-break, applied **after** filtering
- [X] T055 [US4] Extend the `list` handler in `src/main/java/com/atria/todo/cli/CliRunner.java` to accept `--tag`, `--status` and `--sort`, rejecting any `--sort` value other than `priority` with `VALIDATION_ERROR`, and keeping the no-options behaviour from T036 unchanged

**Checkpoint**: Filtering and ordering work; US1–US4 all stand independently.

---

## Phase 7: User Story 5 - Correct and remove tasks (Priority: P3)

**Goal**: Change only the named fields of a task, and remove a task without ever recycling its id.

**Independent Test**: Update one field and confirm every other field is untouched and the change survives
into the next invocation; delete a task and confirm it is gone and its id is not reissued.

### Tests for User Story 5

- [X] T056 [P] [US5] Create `src/test/java/com/atria/todo/cli/UpdateCommandTest.java`: against a task titled `Write report`, tagged `work`, at `MEDIUM`, `update 1 --priority HIGH` changes only the priority — title, status, tags **and `createdAt`** unchanged (AC-07, FR-009, FR-024); the change is visible to a later `get 1`; `update 1` with no field options exits `2`; `update 99 --title x` exits `4`; `update 1 --tags " B,a,b "` yields `["a","b"]`, replacing the set rather than merging
- [X] T057 [P] [US5] Create `src/test/java/com/atria/todo/cli/DeleteCommandTest.java`: `delete 3` prints exactly `{"deleted": 3}` and exits `0`; the task no longer appears in `list`; `delete 99` exits `4`; and `lastIssuedId` is unchanged by the delete so the next `add` is `4` (AC-05)

### Implementation for User Story 5

- [X] T058 [US5] Add `update` to `src/main/java/com/atria/todo/service/TaskService.java`: apply only the supplied fields, running the supplied title through `TaskValidator.validateTitle` and the supplied tags through `normaliseTags`; `id`, `status` and `createdAt` must be untouchable here — status changes go through `move` only (FR-024)
- [X] T059 [US5] Add `delete` to `src/main/java/com/atria/todo/service/TaskService.java`: raise `NOT_FOUND` for an absent id, remove the task, and leave `lastIssuedId` unchanged
- [X] T060 [US5] Add the `update` and `delete` handlers to `src/main/java/com/atria/todo/cli/CliRunner.java`: `update` requires at least one of `--title`, `--priority`, `--tags` and rejects the no-field case with `VALIDATION_ERROR` **before** the store is opened; `delete` prints `{"deleted": <id>}`

**Checkpoint**: All six commands implemented. All five stories independently functional.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T061 [P] Create `README.md` at the repository root: what the tool does, how to build it, the invocation contract, the six commands, the exit-code table, and the store format. Link to `specs/001-smart-todo-cli/quickstart.md`
- [X] T062 Raise JaCoCo line coverage to **≥ 80%**: run `mvn -B org.jacoco:jacoco-maven-plugin:0.8.12:prepare-agent test org.jacoco:jacoco-maven-plugin:0.8.12:report` on the command line only — **do not** add the plugin to `pom.xml` — read `target/site/jacoco/jacoco.csv`, and add unit tests under `src/test/java/com/atria/todo/` for whatever is uncovered. Error paths carry most of the branching, so cover every `ErrorCode` path rather than only happy paths
- [X] T063 Commit the generated implementation, tag it, and snapshot it **before** the first full gate run: `git add -A`, `git commit -m "Initial generation from /speckit-implement"`, `git tag initial-generation`, then `python .harness/run.py snapshot initial`
- [X] T064 Run the full gate suite: `python .harness/run.py run --label run-01` from the repository root. On failure read the newest report in `evidence/feedback/` and fix the **implementation** — never weaken a test, lower an assertion, add a suppression, or edit anything under `.harness/`. Re-run with the next label (`run-02`, …) until integrity, build, lint, sast, unit and contract are all green
- [X] T065 Walk `specs/001-smart-todo-cli/quickstart.md` end to end against the built jar: the walkthrough, the failure-contract checks, the corrupt-file and zero-byte data-safety checks, and the determinism comparison. Treat any surprise here as a real defect even when every gate is green, since four clarified behaviours are invisible to the contract suite
- [X] T066 Record the final state: `python .harness/run.py snapshot final`

---

## Dependencies & Execution Order

### Phase dependencies

- **Phase 1 Setup (T001–T006)**: no dependencies. T006's probe result may change how T019 and T022 are written, which is the whole point of running it first
- **Phase 2 Foundational (T007–T028)**: depends on Setup. **Blocks every user story**
- **Phase 3 US1 (T029–T037)**: depends on Phase 2
- **Phase 4 US2 (T038–T042)**: depends on Phase 2
- **Phase 5 US3 (T043–T051)**: depends on Phase 2; T046's `update 99` case needs US5's `update` handler (T060) to be meaningful, so either run Phase 5 after Phase 7 or write that one assertion last
- **Phase 6 US4 (T052–T055)**: depends on Phase 2 and on T036 (the `list` handler it extends)
- **Phase 7 US5 (T056–T060)**: depends on Phase 2
- **Phase 8 Polish (T061–T066)**: depends on every story being complete

### Within Phase 2

T007, T008, T009 are independent. T010 needs T009. T011 needs T007 and T008. T012 needs T010.
T016 needs T011. T017 needs T011. T018 needs T016 and T017. T019 needs T018.
T021 is independent. T022 needs T010 and T021. T024 needs T018. T025 needs T022 and T024.
T026 needs T025. T027 needs T019.

### Within each user story

Tests first, confirmed failing, then service methods, then the CLI handler, then end-to-end verification.

### Parallel opportunities

- Setup: T003 alongside T002
- Domain enums: T007, T008, T009 together
- Domain tests: T013, T014, T015 together
- Foundational tests: T020, T023, T028 together
- US1 tests: T029, T030, T031, T032 together
- US2 tests: T038, T039 together
- US3 tests: T043, T044, T045, T046, T047 together — the largest parallel block
- US5 tests: T056, T057 together
- Across stories: once Phase 2 is done, US2, US4 and US5 touch different handler methods and different test files, so they can proceed independently

Note the practical ceiling: `CliRunner.java` and `TaskService.java` are each touched by several stories, so
tasks editing the same file must not run simultaneously even when their stories are independent.

---

## Parallel Example: User Story 3

```bash
# All five US3 test files are independent - write them together:
Task: "Store shape validation tests in src/test/java/com/atria/todo/storage/StoreCodecValidationTest.java"
Task: "Zero-byte store test in src/test/java/com/atria/todo/storage/ZeroByteStoreTest.java"
Task: "Stream and exit-code contract tests in src/test/java/com/atria/todo/cli/ErrorContractTest.java"
Task: "Fault precedence tests in src/test/java/com/atria/todo/cli/FaultPrecedenceTest.java"
Task: "Error message determinism tests in src/test/java/com/atria/todo/cli/ErrorMessageTest.java"
```

---

## Implementation Strategy

### MVP first (User Story 1 only)

1. Phase 1 Setup — including the gate probe, which is cheap now and expensive later
2. Phase 2 Foundational — blocks everything
3. Phase 3 US1
4. **Stop and validate**: `add`, `get` and `list` work and persist across processes
5. At this point the tool is genuinely useful, and AC-01 through AC-05, AC-08, AC-13 and AC-14 are satisfied

### Incremental delivery

1. Setup + Foundational → pipeline ready
2. US1 → persistent task capture (**MVP**)
3. US2 → status lifecycle
4. US3 → safe to script, damaged stores refused
5. US4 → filtering and ordering
6. US5 → corrections and removal
7. Polish → coverage, full gate run, snapshots

### Single-developer ordering

Built one at a time in the order above. US3 is placed third despite being tied on priority with US2 because
its data-safety work protects everything added after it — and because its tests are the only guard on four
behaviours the contract gate cannot see.

---

## Notes

- `[P]` means a different file with no dependency on incomplete work
- `[Story]` labels map each task to a user story for traceability back to the acceptance criteria
- Commit after each task or logical group; stop at any checkpoint to validate a story on its own
- Every acceptance criterion AC-01..AC-16 is covered: AC-01..05 and AC-13/14 in US1, AC-06 in US1/US2/US5,
  AC-07 in US5, AC-08 in US1, AC-09..11 in US4, AC-12 in US2, AC-15/16 in US3
- The four behaviours the locked contract suite cannot see — zero-byte rejection, fault precedence,
  repeated-option rejection, and error-message wording — are covered by T044, T046, T023 and T047. Do not
  drop or weaken those tests; nothing else will catch a regression in them

# Feature Specification: Smart Todo CLI

**Feature Branch**: `001-smart-todo-cli`

**Created**: 2026-11-14

**Status**: Draft

**Input**: User description: "Build \"Smart Todo CLI\": a command-line task manager for a single local user that stores tasks in a JSON file." (full invocation contract, command list and acceptance criteria AC-01..AC-16 as supplied)

## Invocation Contract *(mandatory)*

The command line **is** the product surface for this feature, so the contract below is specification,
not implementation detail. It is asserted by the external black-box contract suite.

```
java -jar target/todo-cli.jar --data <path-to-json> <command> [options]
```

- **Success**: exactly one JSON document on stdout, nothing on stderr, exit code `0`.
- **Failure**: nothing on stdout; exactly one JSON document on stderr:
  `{"error":{"code":"<CODE>","message":"<text>"}}`; documented non-zero exit code.

| Exit | Code name | Meaning |
| --- | --- | --- |
| `0` | — | Success |
| `2` | `VALIDATION_ERROR` | Bad arguments, bad values, unknown command, unknown option |
| `3` | `INVALID_TRANSITION` | Status change not allowed by the transition rules |
| `4` | `NOT_FOUND` | No task with the requested id |
| `5` | `STORAGE_ERROR` | Data file unreadable, unparseable, or not writable |

### Commands

| Command | Form | Prints on success |
| --- | --- | --- |
| add | `add --title <text> [--priority LOW\|MEDIUM\|HIGH] [--tags a,b,c]` | the created task |
| get | `get <id>` | the task |
| list | `list [--tag <tag>] [--status <STATUS>] [--sort priority]` | an array of tasks |
| update | `update <id> [--title <text>] [--priority <P>] [--tags a,b]` | the updated task |
| delete | `delete <id>` | `{"deleted": <id>}` |
| move | `move <id> <STATUS>` | the updated task |

## Clarifications

### Session 2026-11-14

- Q: When the data file exists but is completely empty (zero bytes), should the tool treat it as an empty task list and succeed, or refuse it as damaged? → A: Refuse it as damaged — exit `5` / `STORAGE_ERROR`, file left untouched (Option B)
- Q: When the data file holds valid JSON but not the shape this tool writes, should the tool refuse it as damaged, or accept what it can? → A: Store is an object wrapper holding the task collection and a persisted id counter; any other shape, including a bare array, is refused with exit `5` and the file left untouched (Option A)
- Q: When a command is wrong in two ways at once (e.g. `update 99` with no fields), which error should win? → A: Arguments → storage → lookup → transition. `update 99` with no fields is exit `2`; a bad argument against a corrupt file is exit `2` (Option A)
- Q: When the same option is given twice in one command (e.g. `add --title "a" --title "b"`), should the tool reject it or silently use one value? → A: Reject any repeated option with exit `2` / `VALIDATION_ERROR` (Option A)
- Q: Should the error `message` text be fixed and repeatable, or may it vary by quoting the bad value or an underlying system error? → A: Fixed wording per failure, plus the user-supplied offending value where one exists, and nothing machine-specific (Option B)

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Capture a task and get it back (Priority: P1)

A single local user records something they must not forget, with as little ceremony as possible, and
can read it back later in a new session. One option — the title — is all that is required; everything
else takes a sensible default.

**Why this priority**: This is the whole point of a todo tool. Without capture and retrieval there is
no product. Every other story operates on tasks that this story creates.

**Independent Test**: Run `add --title "Buy milk"`, then `list` and `get 1` in separate invocations
against the same data file. Delivers a working, persistent task recorder on its own.

**Acceptance Scenarios**:

1. **Given** no data file exists, **When** the user runs `add --title "Buy milk"`, **Then** a task is
   created with id `1`, priority `MEDIUM`, status `TODO`, tags `[]`, a `createdAt` UTC timestamp, and
   the created task is printed with exit `0`. *(AC-01)*
2. **Given** no data file exists, **When** the user runs `list`, **Then** `[]` is printed with exit
   `0` and no file is required to pre-exist. *(AC-14)*
3. **Given** a task was added in an earlier invocation, **When** the user runs `get 1` in a new
   invocation against the same data file, **Then** the stored task is printed unchanged. *(AC-13)*
4. **Given** the user supplies `--title "   Pay rent  "`, **When** the task is created, **Then** the
   stored and printed title is `Pay rent`. *(AC-02)*
5. **Given** the user supplies `--title "    "` or a title of 201 characters, **When** the command
   runs, **Then** it fails with exit `2` / `VALIDATION_ERROR` and no task is created. *(AC-02)*
6. **Given** the user supplies a title of exactly 200 characters, **When** the command runs, **Then**
   it succeeds. *(AC-02, boundary)*
7. **Given** the user supplies `--priority high`, **When** the task is created, **Then** the priority
   is stored and printed as `HIGH`. *(AC-03)*
8. **Given** the user supplies `--priority urgent`, **When** the command runs, **Then** it fails with
   exit `2` / `VALIDATION_ERROR`. *(AC-03)*
9. **Given** the user supplies `--tags " Work,home,WORK,, urgent "`, **When** the task is created,
   **Then** the tags are `["home","urgent","work"]` — trimmed, lower-cased, de-duplicated, empty
   entries dropped, sorted ascending. *(AC-04)*
10. **Given** three tasks exist and task `3` is deleted, **When** another task is added, **Then** its
    id is `4`; the id `3` is never reused. *(AC-05)*

---

### User Story 2 - Move work through its stages (Priority: P2)

The user advances a task from not-started to in-progress to finished, and can send work backwards when
they were wrong about it. Illegal jumps are refused outright rather than silently accepted, so the
status field stays trustworthy.

**Why this priority**: Status tracking is what separates a todo tool from a notepad. It depends only on
tasks existing (P1) and is independently demonstrable.

**Independent Test**: Walk one task through `TODO -> IN_PROGRESS -> DONE -> TODO`, then attempt a
forbidden jump and confirm the task is untouched.

**Acceptance Scenarios**:

1. **Given** a task in `TODO`, **When** the user runs `move 1 IN_PROGRESS`, **Then** the task is
   updated and printed with status `IN_PROGRESS`. *(AC-12)*
2. **Given** a task in `IN_PROGRESS`, **When** the user moves it to `DONE` or back to `TODO`, **Then**
   the move succeeds. *(AC-12)*
3. **Given** a task in `DONE`, **When** the user moves it to `TODO`, **Then** the move succeeds.
   *(AC-12)*
4. **Given** a task in `TODO`, **When** the user runs `move 1 DONE`, **Then** it fails with exit `3` /
   `INVALID_TRANSITION` and the task is unchanged. *(AC-12)*
5. **Given** a task in `TODO`, **When** the user runs `move 1 TODO`, **Then** it fails with exit `3` /
   `INVALID_TRANSITION` — a move to the current status is not a no-op. *(AC-12)*
6. **Given** a task in `DONE`, **When** the user runs `move 1 IN_PROGRESS`, **Then** it fails with
   exit `3` / `INVALID_TRANSITION`. *(AC-12)*
7. **Given** any task, **When** the user runs `move 1 FINISHED`, **Then** it fails with exit `2` /
   `VALIDATION_ERROR`, because the target is not a known status at all. *(AC-12 + precedence rule)*

---

### User Story 3 - Trust the tool inside a script (Priority: P2)

The user drives the tool from a shell script or another program. Success and failure are distinguishable
without parsing prose: stdout carries data only, stderr carries a machine-readable error only, and the
exit code names the failure class. A damaged data file stops the tool instead of being overwritten.

**Why this priority**: The output contract is what makes the tool composable, and the data-safety rule
protects the user's only copy of their tasks. Both are cross-cutting and independently testable.

**Independent Test**: Run a deliberately bad invocation and assert stdout is empty, stderr holds one
error document and the exit code matches; then corrupt the data file and confirm it is byte-for-byte
unchanged after a failed run.

**Acceptance Scenarios**:

1. **Given** any valid state, **When** a command succeeds, **Then** stdout holds exactly one JSON
   document and the exit code is `0`.
2. **Given** any failing command, **When** it fails, **Then** stdout is empty and stderr holds exactly
   one document `{"error":{"code":"<CODE>","message":"<text>"}}` with the code matching the exit code.
3. **Given** a data file whose contents are not valid JSON, **When** the user runs `list`, **Then** it
   fails with exit `5` / `STORAGE_ERROR`. *(AC-15)*
4. **Given** a data file whose contents are not valid JSON, **When** the user runs `add --title "x"`,
   **Then** it fails with exit `5` / `STORAGE_ERROR` and the file is byte-for-byte unchanged. *(AC-15)*
5. **Given** any state, **When** the user runs an unknown command such as `frobnicate`, **Then** it
   fails with exit `2` / `VALIDATION_ERROR`. *(AC-16)*
6. **Given** any state, **When** the user omits `--data`, **Then** it fails with exit `2` /
   `VALIDATION_ERROR`. *(AC-16)*
7. **Given** any state, **When** the user passes an unknown option such as `--colour red`, **Then** it
   fails with exit `2` / `VALIDATION_ERROR`. *(AC-16)*
8. **Given** the same starting data and the same sequence of commands, **When** the sequence is run
   twice, **Then** stdout and exit codes are identical both times, `createdAt` values excepted.
   *(Constitution IV)*

---

### User Story 4 - Find the right task among many (Priority: P3)

Once the list is long, the user narrows it by tag or status and orders it by importance rather than by
entry order, combining filters to answer a specific question.

**Why this priority**: Valuable, but a user with a short list is already served by P1. Needs a
populated store to be meaningful.

**Independent Test**: Seed four tasks across priorities and tags, then verify each filter, the priority
ordering, and a combined filter-plus-sort query.

**Acceptance Scenarios**:

1. **Given** four tasks, **When** the user runs `list` with no options, **Then** all tasks are returned
   ordered by id ascending. *(AC-08)*
2. **Given** tasks tagged `work` and `home`, **When** the user runs `list --tag WORK`, **Then** only
   the `work`-tagged tasks are returned — the match is case-insensitive. *(AC-09)*
3. **Given** no task carries the requested tag, **When** the user runs `list --tag nothing`, **Then**
   `[]` is returned with exit `0`; an empty result is not an error. *(AC-09)*
4. **Given** one task is `IN_PROGRESS`, **When** the user runs `list --status IN_PROGRESS`, **Then**
   only that task is returned. *(AC-10)*
5. **Given** any state, **When** the user runs `list --status BLOCKED`, **Then** it fails with exit `2`
   / `VALIDATION_ERROR`. *(AC-10)*
6. **Given** tasks of mixed priority, **When** the user runs `list --sort priority`, **Then** they are
   ordered `HIGH`, `MEDIUM`, `LOW`, with ties broken by id ascending. *(AC-11)*
7. **Given** tasks of mixed priority and tags, **When** the user runs
   `list --tag home --sort priority`, **Then** filters combine with AND and the surviving tasks are
   priority-ordered. *(AC-11)*

---

### User Story 5 - Correct and remove tasks (Priority: P3)

The user fixes a typo, re-prioritises, re-tags, or deletes a task that no longer matters — changing only
what they named and nothing else.

**Why this priority**: Important for day-to-day use but the last piece needed for a viable product;
capture, status and retrieval all stand without it.

**Independent Test**: Update one field of a task and confirm every other field is untouched and the
change survives into the next invocation; delete a task and confirm it is gone.

**Acceptance Scenarios**:

1. **Given** a task titled `Write report` tagged `work` at `MEDIUM`, **When** the user runs
   `update 1 --priority HIGH`, **Then** only the priority changes; title, status and tags are
   unchanged. *(AC-07)*
2. **Given** that update, **When** the user runs `get 1` in a new invocation, **Then** the new priority
   is still there. *(AC-07, AC-13)*
3. **Given** a task exists, **When** the user runs `update 1` with no field options, **Then** it fails
   with exit `2` / `VALIDATION_ERROR`. *(AC-07)*
4. **Given** a task exists, **When** the user runs `update 1 --tags " B,a,b "`, **Then** the tags are
   normalised exactly as on create: `["a","b"]`. *(AC-04, AC-07)*
5. **Given** task `3` exists, **When** the user runs `delete 3`, **Then** `{"deleted": 3}` is printed
   with exit `0` and the task no longer appears in `list`. *(AC-05)*
6. **Given** no task with id `99`, **When** the user runs `get 99`, `update 99 --title x`, `delete 99`
   or `move 99 DONE`, **Then** each fails with exit `4` / `NOT_FOUND`. *(AC-06)*
7. **Given** any state, **When** the user runs `get abc`, **Then** it fails with exit `2` /
   `VALIDATION_ERROR` because the id is not an integer. *(AC-06)*

---

### Edge Cases

- **Missing data file**: treated as an empty task list; read commands succeed, write commands create
  the file. *(AC-14)*
- **Corrupt data file**: never repaired, never overwritten, never truncated — exit `5` with the bytes
  left exactly as found. *(AC-15)*
- **Zero-byte data file**: refused as damaged, exit `5`, file left untouched — not read as an empty
  list. A file that exists but holds nothing is indistinguishable from a truncated write, so the tool
  stops rather than overwrite a possible casualty. Starting clean is the *missing* file path (AC-14),
  which needs no pre-created file.
- **Title boundary**: 200 characters accepted, 201 rejected; length is measured after trimming.
- **Whitespace-only title**: rejected as blank, not stored as an empty string.
- **Tags that collapse to nothing**: `--tags " , ,"` yields `[]`, not an error.
- **Tag case**: stored lower-cased, so `--tag WORK` and `--tag work` match the same tasks.
- **Move to the current status**: an error (`3`), deliberately not a silent success.
- **Unknown status word in `move`**: a validation failure (`2`), not a transition failure (`3`).
- **Non-existent id combined with another fault**: argument faults are reported before lookup, so
  `update 99` with no field options fails with `2`, not `4`.
- **Empty result set**: `[]` with exit `0`; absence of matches is not `NOT_FOUND`.
- **Deleted ids**: never reissued, even when the deleted task held the highest id.
- **Interrupted write**: the data file is either fully the old content or fully the new content; no
  partially written file is ever left behind.
- **Unwritable target location**: exit `5` / `STORAGE_ERROR`.

## Requirements *(mandatory)*

### Functional Requirements

**Invocation and output**

- **FR-001**: System MUST accept `--data <path>` before the command name and MUST fail with exit `2` /
  `VALIDATION_ERROR` when it is absent. *(AC-16)*
- **FR-002**: System MUST print exactly one JSON document on stdout and exit `0` on success, with
  nothing written to stderr.
- **FR-003**: System MUST, on any failure, write nothing to stdout and write exactly one document
  `{"error":{"code":"<CODE>","message":"<text>"}}` to stderr, where `<CODE>` is one of
  `VALIDATION_ERROR`, `INVALID_TRANSITION`, `NOT_FOUND`, `STORAGE_ERROR`.
- **FR-003a**: System MUST use fixed, repeatable wording for each distinct failure, optionally followed
  by the user-supplied value that caused it (for example `unknown priority: urgent`). The message MUST
  NOT contain anything machine-specific — no file paths, no underlying system or parser error text, no
  stack traces, no timestamps — so that the same command produces the same error document on any
  machine. *(Clarification 2026-11-14)*
- **FR-004**: System MUST map each failure class to its exit code exactly: `2` `VALIDATION_ERROR`,
  `3` `INVALID_TRANSITION`, `4` `NOT_FOUND`, `5` `STORAGE_ERROR`.
- **FR-005**: System MUST reject an unknown command and any unknown option with exit `2`. *(AC-16)*
- **FR-005a**: System MUST reject a repeated option with exit `2`, rather than letting one occurrence
  win silently. This applies to every option including `--tags`, which replaces the tag set rather than
  accumulating across repeats. System MUST also reject an option that is missing its value, and any
  extra positional argument beyond those the command defines.
  *(Clarification 2026-11-14)*
- **FR-006**: System MUST evaluate faults in this fixed order, so that one invocation always produces
  one predictable code: (1) `--data` present, (2) command known, (3) option names known and option
  values well-formed, (4) command arity and required options satisfied, (5) data file readable, of the
  expected shape and parseable, (6) requested id exists, (7) requested status change allowed, (8) write
  succeeds. The first failing check decides the exit code; later checks MUST NOT run. Required
  consequences: `update 99` with no field options exits `2` (not `4`); `move <id> FINISHED` exits `2`
  (not `3`); a malformed argument against a corrupt data file exits `2` (not `5`); and the data file
  MUST NOT be opened at all when a command fails checks 1 through 4.
  *(Clarification 2026-11-14)*

**Task shape**

- **FR-007**: System MUST represent a task with exactly these fields: `id` (integer), `title` (string),
  `priority` (`LOW`|`MEDIUM`|`HIGH`), `status` (`TODO`|`IN_PROGRESS`|`DONE`), `tags` (array of strings),
  `createdAt` (ISO-8601 UTC string ending in `Z`).
- **FR-008**: System MUST assign ids starting at `1`, increasing by `1` per created task, and MUST
  NEVER reissue the id of a deleted task. *(AC-05)*
- **FR-009**: System MUST set `createdAt` once at creation and MUST NOT alter it on any later update.

**Validation and normalisation**

- **FR-010**: System MUST trim the title and MUST reject a title that is empty or blank after trimming,
  or longer than 200 characters after trimming, with exit `2`. A 200-character title MUST be accepted.
  *(AC-02)*
- **FR-011**: System MUST accept priority values case-insensitively, MUST store and print them
  upper-case, and MUST reject any value outside the three allowed ones with exit `2`. *(AC-03)*
- **FR-012**: System MUST accept status values case-insensitively wherever a status is supplied, MUST
  store and print them upper-case, and MUST reject any value outside the three allowed ones with exit
  `2`. *(AC-10, AC-12)*
- **FR-013**: System MUST normalise tags on both create and update: split on commas, trim each entry,
  lower-case it, drop empty entries, remove duplicates, and print the result sorted ascending. *(AC-04)*
- **FR-014**: System MUST reject a non-integer id with exit `2` before attempting any lookup. *(AC-06)*

**Commands**

- **FR-015**: Users MUST be able to create a task with `add`, supplying `--title` and optionally
  `--priority` and `--tags`; omitted options MUST default to priority `MEDIUM`, status `TODO` and tags
  `[]`. `--title` MUST be required. *(AC-01)*
- **FR-016**: Users MUST be able to read one task with `get <id>`.
- **FR-017**: Users MUST be able to list tasks with `list`, which MUST return all tasks ordered by id
  ascending when no options are given. *(AC-08)*
- **FR-018**: System MUST support `list --tag <tag>`, matching case-insensitively and returning only
  tasks carrying that tag. *(AC-09)*
- **FR-019**: System MUST support `list --status <STATUS>`, returning only tasks in that status, and
  MUST reject an unknown status with exit `2`. *(AC-10)*
- **FR-020**: System MUST support `list --sort priority`, ordering `HIGH`, then `MEDIUM`, then `LOW`,
  breaking ties by id ascending. *(AC-11)*
- **FR-021**: System MUST combine `list` filters with AND, and MUST apply sorting after filtering.
  *(AC-11)*
- **FR-022**: System MUST return an empty array with exit `0` when a `list` query matches no task.
  *(AC-09)*
- **FR-023**: Users MUST be able to change a task with `update <id>`, supplying at least one of
  `--title`, `--priority`, `--tags`; the system MUST change only the supplied fields and MUST reject an
  update with no field options with exit `2`. *(AC-07)*
- **FR-024**: System MUST NOT allow `update` to change `id`, `status` or `createdAt`; status changes go
  through `move` only.
- **FR-025**: Users MUST be able to remove a task with `delete <id>`, which MUST print
  `{"deleted": <id>}`. *(AC-05)*
- **FR-026**: Users MUST be able to change a task's status with `move <id> <STATUS>`, which MUST print
  the updated task.
- **FR-027**: System MUST allow exactly these transitions: `TODO -> IN_PROGRESS`,
  `IN_PROGRESS -> DONE`, `IN_PROGRESS -> TODO`, `DONE -> TODO`. Every other pairing, including a move
  to the task's current status, MUST fail with exit `3` and leave the task unchanged. *(AC-12)*
- **FR-028**: System MUST fail with exit `4` for `get`, `update`, `delete` or `move` against an id that
  does not exist. *(AC-06)*

**Storage**

- **FR-029**: System MUST persist tasks in the file given by `--data` so that they are visible to later
  invocations using the same path. *(AC-13)*
- **FR-029a**: System MUST store its data as a single JSON object carrying both the task collection and
  the id counter, so that the never-reuse rule survives deletion of the highest id. The counter MUST
  record the highest id ever issued, MUST NOT be recomputed from the tasks currently present, and MUST
  be written on every mutating command. *(AC-05, Clarification 2026-11-14)*
- **FR-029b**: System MUST validate the shape of the data file before use and MUST fail with exit `5`,
  leaving the file untouched, when the content is valid JSON but not this shape — including a bare JSON
  array, a JSON scalar, a missing task collection, a missing or non-integer id counter, or a task entry
  whose required fields are absent or of the wrong type. An unrecognised shape MUST NEVER be repaired,
  upgraded or overwritten. *(Clarification 2026-11-14)*
- **FR-030**: System MUST treat a missing data file as an empty task list, without creating it for
  read-only commands. *(AC-14)*
- **FR-031**: System MUST fail with exit `5` when the data file is not valid JSON, and MUST leave its
  contents byte-for-byte unchanged. *(AC-15)*
- **FR-031a**: System MUST treat an existing zero-byte data file as damaged, failing with exit `5` and
  leaving the file untouched, for both read and write commands. A zero-byte file MUST NOT be read as an
  empty task list; only an absent file is a clean start. *(Clarification 2026-11-14)*
- **FR-032**: System MUST write the data file atomically — a temporary file in the same directory
  followed by an atomic move — so that no reader ever observes a partially written file.
- **FR-033**: System MUST fail with exit `5` when the data file cannot be read or written for reasons
  other than malformed content.

**Determinism and safety**

- **FR-034**: System MUST produce identical stdout, identical stderr and identical exit codes for an
  identical command sequence against identical starting data, with `createdAt` the sole permitted
  variation. The determinism guarantee covers error documents, not only success output.
- **FR-035**: System MUST NOT use randomness, generated unique identifiers, locale-dependent
  formatting, or ordering that depends on internal storage layout.
- **FR-036**: System MUST NOT execute, interpret or shell out to any user-supplied value, and MUST NOT
  access the network.
- **FR-037**: System MUST NOT write secrets, credentials, the data file path, or any part of the data
  file's contents into error messages or any other output.

### Key Entities

- **Task**: One thing the user intends to do. Carries an immutable identity (`id`) and creation instant
  (`createdAt`), a human-readable `title`, an importance (`priority`), a lifecycle position (`status`),
  and a set of normalised labels (`tags`). Tasks are independent; none references another.
- **Task Store**: The single JSON file named by `--data` that holds every task for one local user. It is
  a JSON *object* carrying two things: the collection of tasks, and an **Id Counter** recording the
  highest id ever issued. It is the sole source of truth and the only thing that survives between
  invocations. Any other shape is damaged data, never a store to be upgraded.
- **Id Counter**: The highest task id ever issued in this store, persisted alongside the tasks. It only
  ever increases, which is what makes a deleted id unreachable forever rather than merely absent.
- **Error Report**: The single document emitted on stderr when a command fails, pairing a stable
  machine-readable `code` with a human-readable `message`.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: 100% of the acceptance scenarios derived from AC-01..AC-16 pass in the black-box contract
  suite, with zero failures.
- **SC-002**: Running the same command sequence twice from the same starting data produces identical
  output and exit codes in 100% of runs, `createdAt` excepted.
- **SC-003**: After any failed command, the data file is byte-for-byte identical to its state before the
  command — zero bytes changed, zero tasks lost.
- **SC-004**: A new user can record their first task and read it back using two commands and no
  configuration file, no installation step beyond the single build artifact, and no network access.
- **SC-005**: Every failure path reports a non-zero exit code and a machine-readable error document with
  an empty stdout — 100% of error cases, so a calling script never has to parse prose.
- **SC-006**: Any single command against a store of 1,000 tasks completes in under 2 seconds on a
  typical laptop.
- **SC-007**: All six project quality gates report green: integrity, build, lint, static analysis, unit
  tests at a minimum of 80% line coverage, and contract tests.

## Assumptions

Reasonable defaults chosen where the description was silent. Each is cheap to change if wrong.

- **Single user, single store**: no concurrent invocations against the same data file, no locking, no
  multi-user access control. The file is the user's own.
- **`--tags` takes one comma-separated value**; repeating the option or passing extra positional values
  is a validation failure. An update to `--tags` replaces the whole set rather than merging.
- **`--tag` filter takes exactly one tag**; multi-tag filtering is out of scope.
- **`--sort` accepts only `priority`**; any other value fails with exit `2`.
- **Exact key names inside the store** are left to the implementation plan; this spec fixes the store's
  shape (an object carrying tasks plus an id counter) and its strictness, not its spelling.
- **Tags have no length or character limit** beyond being non-empty after trimming.
- **Titles may contain any character** including commas and quotes; only length and blankness are
  validated.
- **`createdAt` is recorded in UTC at second or finer precision**, formatted ISO-8601 with a trailing
  `Z`, and is the only non-deterministic value in the output.
- **Parent directory must already exist**; the tool creates the data file but not the directories above
  it, and reports `5` if it cannot.
- **Output JSON field order and formatting are stable** across runs, as required by determinism, but no
  specific indentation is promised to the user.
- **SC-006's 1,000-task / 2-second target** is an assumed ceiling for a personal todo list; no volume
  requirement was stated.
- **Localisation is out of scope**: messages are English, and no locale affects parsing, formatting or
  ordering.

## Out of Scope

- Recurring tasks, due dates, reminders, subtasks, dependencies and priorities beyond the three levels.
- Searching or filtering by title text, by date, or by more than one tag at a time.
- Editing a task's status through `update`, or bulk operations across many tasks in one command.
- Undo, history, archiving, or recovery of deleted tasks.
- Interactive or shell-completion modes, colour output, configuration files, environment-variable
  defaults.
- Multi-user access, synchronisation, networking, encryption of the data file.
- Migration of data files written by other tools or by future versions of this one.

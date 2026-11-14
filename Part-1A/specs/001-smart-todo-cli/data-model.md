# Phase 1 Data Model: Smart Todo CLI

**Date**: 2026-11-14 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Entities are drawn from the spec's Key Entities section; every validation rule below traces to a numbered
requirement. The on-disk encoding of these entities is in [contracts/store-schema.md](./contracts/store-schema.md).

---

## Task

One thing the user intends to do. Immutable: every mutating operation returns a new instance rather than
modifying one in place, which keeps the "task is unchanged on a rejected move" rule (AC-12) structural
rather than a matter of remembering to roll back.

| Field | Type | Mutable after create | Rules |
|---|---|---|---|
| `id` | `int` | No | Assigned by the store, never by the user. ≥ 1. Unique within a store. Never reused (FR-008) |
| `title` | `String` | Yes, via `update` | Trimmed. Non-blank after trimming. Length ≤ 200 **after** trimming (FR-010) |
| `priority` | `Priority` | Yes, via `update` | One of `LOW`, `MEDIUM`, `HIGH`. Defaults to `MEDIUM` (FR-015) |
| `status` | `Status` | Yes, via `move` **only** | One of `TODO`, `IN_PROGRESS`, `DONE`. Defaults to `TODO`. `update` must not change it (FR-024) |
| `tags` | `List<String>` | Yes, via `update` | Normalised set, sorted ascending. May be empty. Replaced wholesale, never merged (FR-013, FR-005a) |
| `createdAt` | `Instant` | No | Set once at creation, truncated to seconds, rendered ISO-8601 UTC ending in `Z` (FR-009, R1) |

**Java representation**: `final class Task` with private final fields and accessor methods. The
constructor copies the incoming tags list and `tags()` returns `List.copyOf(...)` — both required to avoid
SpotBugs `EI_EXPOSE_REP` / `EI_EXPOSE_REP2` at threshold `Low` (research R3). No Jackson annotations:
`domain` must not depend on the serialisation library (Constitution VI).

### Title normalisation (FR-010)

1. Trim leading and trailing whitespace.
2. Reject if the result is empty → `VALIDATION_ERROR`.
3. Reject if the result exceeds 200 characters → `VALIDATION_ERROR`.
4. Store the trimmed value.

Length is counted on the trimmed string, so `"  " + 200 chars + "  "` is accepted. Exactly 200 is
accepted; 201 is rejected — the locked suite asserts both ends of this boundary.

### Tag normalisation (FR-013)

Applied identically on `add` and `update`:

1. Split the option value on `,`.
2. Trim each element.
3. Lower-case each element using `Locale.ROOT` (never the default-locale overload — research R8).
4. Drop empty elements.
5. De-duplicate.
6. Sort ascending by natural `String` order.

Worked example from the locked suite: `" Work,home,WORK,, urgent "` → `["home","urgent","work"]`.
A value that collapses to nothing (`" , ,"`) yields `[]` and is **not** an error.

---

## Priority

Closed enum with an explicit sort rank, because the display order is not the alphabetical order.

| Constant | Sort rank | Notes |
|---|---|---|
| `HIGH` | 0 | Sorts first under `--sort priority` |
| `MEDIUM` | 1 | Default for a new task |
| `LOW` | 2 | Sorts last |

Parsing is case-insensitive via `Locale.ROOT`; output is always upper-case (FR-011). An unrecognised
value is `VALIDATION_ERROR`, never a silent fallback to `MEDIUM`.

The rank is declared explicitly rather than relying on `Enum.ordinal()` or declaration order, so that
reordering the constants cannot silently change the sort contract asserted by AC-11.

---

## Status

Closed enum, no rank — the lifecycle order is expressed by the transition table, not by a comparison.

`TODO` · `IN_PROGRESS` · `DONE`

Parsing is case-insensitive; output is always upper-case (FR-012). An unrecognised value is
`VALIDATION_ERROR` (exit 2) — **not** `INVALID_TRANSITION`. This precedence is explicit in FR-006 and is
asserted by the locked suite (`move 1 FINISHED` → exit 2).

### State machine (FR-027, AC-12)

```text
        ┌──────────────────────────────┐
        │                              │
        ▼                              │
     ┌──────┐  ──────────────▶  ┌─────────────┐  ──────────▶  ┌──────┐
     │ TODO │                   │ IN_PROGRESS │               │ DONE │
     └──────┘  ◀──────────────  └─────────────┘               └──────┘
        ▲                                                         │
        └─────────────────────────────────────────────────────────┘
```

Exactly four legal moves:

| From | To | |
|---|---|---|
| `TODO` | `IN_PROGRESS` | start work |
| `IN_PROGRESS` | `DONE` | finish |
| `IN_PROGRESS` | `TODO` | put back |
| `DONE` | `TODO` | reopen |

Everything else is `INVALID_TRANSITION` (exit 3) with the task left untouched. Notable consequences,
each asserted by the locked suite:

- `TODO → DONE` is **not** allowed — work cannot skip the middle.
- `DONE → IN_PROGRESS` is **not** allowed — reopening goes through `TODO`.
- A move to the task's **current** status is **not** a no-op; it is an error.

**Java representation**: `Transitions` — a `final` class with a private constructor (Checkstyle
`FinalClass` + `HideUtilityClassConstructor`) exposing one predicate over an explicit set of ordered
pairs. Declaring the four pairs as data rather than as nested `if`s keeps it inside the nested-depth and
cyclomatic ceilings and makes the table readable against AC-12.

---

## Task Store

The whole contents of one data file, in memory.

| Field | Type | Rules |
|---|---|---|
| `tasks` | `List<Task>` | Kept in id-ascending order, which makes `list` with no options a straight copy (FR-017) |
| `lastIssuedId` | `int` | Highest id ever issued in this store. ≥ 0. Monotonic (FR-029a) |

### Id allocation (FR-008, AC-05)

```text
newId = ++lastIssuedId      // then persist lastIssuedId
```

The counter is **persisted** and **never recomputed from the tasks present**. This is the whole reason
the store is an object rather than a bare array: with `max(existing id) + 1`, deleting the highest task
would hand its id to the next one, which the locked suite catches directly — add 3, delete 3, next must
be 4.

`delete` removes the task and leaves `lastIssuedId` alone.

---

## Id Counter

Not a separate class — the `lastIssuedId` field of the store — but worth naming as an entity because it
carries its own invariant, independent of the task list:

- Only ever increases, within a run and across runs.
- Greater than or equal to the largest id present.
- Unchanged by `delete`, `update` and `move`.
- Written on every mutating command, so a crash cannot leave the counter behind the task list.

---

## Error Report

The single document written to stderr when a command fails. Not persisted.

| Field | Type | Rules |
|---|---|---|
| `code` | `String` | One of `VALIDATION_ERROR`, `INVALID_TRANSITION`, `NOT_FOUND`, `STORAGE_ERROR` |
| `message` | `String` | Fixed wording per failure, optionally plus the user's offending value. Nothing machine-specific (FR-003a) |

Serialised as `{"error":{"code":"<CODE>","message":"<text>"}}`.

**Java representation**: `ErrorCode` enum pairing each code name with its exit status; `TodoException`
carries one `ErrorCode` plus the message. The enum is the single source of the code↔exit mapping, so the
two cannot disagree.

| `ErrorCode` | Exit | Raised when |
|---|---|---|
| `VALIDATION_ERROR` | 2 | Bad argument, bad value, unknown command, unknown or repeated option, non-integer id |
| `INVALID_TRANSITION` | 3 | A known status that is not a legal move from the task's current status |
| `NOT_FOUND` | 4 | No task with the requested id |
| `STORAGE_ERROR` | 5 | Unreadable, unwritable, malformed, zero-byte or wrong-shaped store; failed write |

Message text carries **no** file path, system error text, stack trace or timestamp — partly for
determinism (identical stderr across machines, FR-034) and partly because FR-037 forbids leaking the path
or file contents.

---

## Validation ownership

Which layer enforces what, so no rule is checked twice or in the wrong place:

| Rule | Owner | Requirement |
|---|---|---|
| `--data` present; command known; option names known, unrepeated, valued; arity | `cli` (`ArgParser`) | FR-001, FR-005, FR-005a |
| Id is an integer | `cli` | FR-014 |
| Priority / status value is a known constant | `cli` (parse) + `domain` (enum) | FR-011, FR-012 |
| `update` supplies at least one field | `cli` | FR-023 |
| Title trim, blankness, length | `domain` (`TaskValidator`) | FR-010 |
| Tag normalisation | `domain` (`TaskValidator`) | FR-013 |
| Transition legality | `domain` (`Transitions`) | FR-027 |
| Id exists | `service` | FR-028 |
| Id allocation and monotonic counter | `storage` (`TaskStore`) | FR-008, FR-029a |
| Store readable, parseable, correctly shaped | `storage` (`StoreCodec`) | FR-029b, FR-031, FR-031a |
| Atomic write | `storage` (`JsonTaskRepository`) | FR-032 |

The order in which these are reached is fixed by FR-006 — arguments, then storage, then lookup, then
transition — which is why the data file is never opened for a command that fails a `cli`-owned rule.

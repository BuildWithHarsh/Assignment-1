# Contract: Command-Line Interface

**Date**: 2026-11-14 | **Spec**: [../spec.md](../spec.md) | **Data model**: [../data-model.md](../data-model.md)

The externally observable contract of the tool: what may be invoked, what comes back on stdout, what comes
back on stderr, and which exit code. This is the surface the locked black-box suite
(`.harness/contract_tests.py`) asserts against; it is authoritative over any convenience the
implementation might prefer.

## Invocation

```text
java -jar target/todo-cli.jar --data <path-to-json> <command> [options]
```

`--data` is global and precedes the command name. Omitting it is `VALIDATION_ERROR`.

## Output rules

| | stdout | stderr | exit |
|---|---|---|---|
| Success | exactly one JSON document | empty | `0` |
| Failure | **empty** | exactly one JSON error document | `2`\|`3`\|`4`\|`5` |

Both sides are binding. On success nothing may be written to stderr; on failure nothing may be written to
stdout. A trailing newline after the document is permitted on either stream.

Error document shape:

```json
{"error":{"code":"VALIDATION_ERROR","message":"unknown priority: urgent"}}
```

`message` is fixed wording per failure, optionally followed by the user's offending value. It contains no
file path, no system error text, no stack trace, no timestamp.

## Task object

Six fields, always in this order:

```json
{
  "id": 1,
  "title": "Buy milk",
  "priority": "MEDIUM",
  "status": "TODO",
  "tags": [],
  "createdAt": "2026-11-14T09:15:30Z"
}
```

`priority` and `status` are always upper-case. `tags` is always sorted ascending, possibly empty.
`createdAt` is an ISO-8601 UTC string ending in `Z`.

## Commands

### `add`

```text
add --title <text> [--priority LOW|MEDIUM|HIGH] [--tags a,b,c]
```

Creates a task. `--title` required. Defaults: priority `MEDIUM`, status `TODO`, tags `[]`.
**Prints**: the created task.

| Condition | Exit | Code |
|---|---|---|
| `--title` missing | 2 | `VALIDATION_ERROR` |
| Title blank after trim, or > 200 chars after trim | 2 | `VALIDATION_ERROR` |
| Priority not one of the three (case-insensitive) | 2 | `VALIDATION_ERROR` |
| Store malformed / zero-byte / wrong shape | 5 | `STORAGE_ERROR` |

### `get`

```text
get <id>
```

**Prints**: the task.

| Condition | Exit | Code |
|---|---|---|
| `<id>` not an integer | 2 | `VALIDATION_ERROR` |
| `<id>` missing or extra positional given | 2 | `VALIDATION_ERROR` |
| No task with that id | 4 | `NOT_FOUND` |

### `list`

```text
list [--tag <tag>] [--status TODO|IN_PROGRESS|DONE] [--sort priority]
```

**Prints**: a JSON array of tasks, `[]` when nothing matches.

- No options → all tasks, id ascending.
- `--tag` → case-insensitive match against the task's normalised tags; exactly one tag value.
- `--status` → exact status match (value parsed case-insensitively).
- `--sort priority` → `HIGH`, `MEDIUM`, `LOW`, ties broken by id ascending.
- Filters combine with **AND**; sorting is applied **after** filtering.

| Condition | Exit | Code |
|---|---|---|
| Unknown status value | 2 | `VALIDATION_ERROR` |
| `--sort` value other than `priority` | 2 | `VALIDATION_ERROR` |
| No task matches | **0** | — (prints `[]`; an empty result is not an error) |

### `update`

```text
update <id> [--title <text>] [--priority <P>] [--tags a,b]
```

Changes only the options supplied. Cannot change `id`, `status` or `createdAt`.
`--tags` **replaces** the whole tag set; it never merges.
**Prints**: the updated task.

| Condition | Exit | Code |
|---|---|---|
| No field option supplied | 2 | `VALIDATION_ERROR` |
| `<id>` not an integer | 2 | `VALIDATION_ERROR` |
| Title or priority value invalid | 2 | `VALIDATION_ERROR` |
| No task with that id | 4 | `NOT_FOUND` |

Precedence note: `update 99` with no field options is **exit 2**, not 4 — argument faults are evaluated
before the store is even opened (FR-006).

### `delete`

```text
delete <id>
```

**Prints**: `{"deleted": <id>}` — exactly this shape.

| Condition | Exit | Code |
|---|---|---|
| `<id>` not an integer | 2 | `VALIDATION_ERROR` |
| No task with that id | 4 | `NOT_FOUND` |

Does not decrement the id counter; the removed id is never reissued.

### `move`

```text
move <id> <STATUS>
```

**Prints**: the updated task.

| Condition | Exit | Code |
|---|---|---|
| `<id>` not an integer | 2 | `VALIDATION_ERROR` |
| `<STATUS>` not a known status word | **2** | `VALIDATION_ERROR` |
| No task with that id | 4 | `NOT_FOUND` |
| Known status, but not a legal move from the current one | **3** | `INVALID_TRANSITION` |
| Target equals the current status | **3** | `INVALID_TRANSITION` |

The 2-versus-3 split is the subtle one: an unrecognised *word* is a validation fault, an unreachable
*state* is a transition fault. `move 1 FINISHED` → 2; `move 1 DONE` from `TODO` → 3.

Legal moves: `TODO→IN_PROGRESS`, `IN_PROGRESS→DONE`, `IN_PROGRESS→TODO`, `DONE→TODO`.

## Global faults

| Condition | Exit | Code |
|---|---|---|
| `--data` absent | 2 | `VALIDATION_ERROR` |
| Unknown command (e.g. `frobnicate`) | 2 | `VALIDATION_ERROR` |
| Unknown option (e.g. `--colour red`) | 2 | `VALIDATION_ERROR` |
| Repeated option (e.g. `--title a --title b`) | 2 | `VALIDATION_ERROR` |
| Option present without a value | 2 | `VALIDATION_ERROR` |
| Data file absent | — | treated as an empty store; reads succeed, writes create it |
| Data file present but zero bytes | 5 | `STORAGE_ERROR` |
| Data file not valid JSON | 5 | `STORAGE_ERROR` |
| Data file valid JSON, wrong shape | 5 | `STORAGE_ERROR` |
| Write fails | 5 | `STORAGE_ERROR` |

In every exit-5 case the data file is left **byte-for-byte unchanged**.

## Fault precedence (FR-006)

First failing check wins; later checks do not run:

1. `--data` present
2. command known
3. option names known, unrepeated, valued; id parses as integer; enum values known
4. command arity and required options satisfied
5. store readable, parseable, correctly shaped
6. requested id exists
7. requested status change legal
8. write succeeds

The data file is **not opened at all** when a command fails checks 1–4.

## Determinism

The same command sequence against the same starting store produces identical stdout, identical stderr and
identical exit codes, with `createdAt` the only permitted variation. No randomness, no generated unique
ids, no locale-dependent case conversion or ordering, no hash-order iteration in any output path.

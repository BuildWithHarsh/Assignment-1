# Contract: On-Disk Store Schema

**Date**: 2026-11-14 | **Spec**: [../spec.md](../spec.md) | **Data model**: [../data-model.md](../data-model.md)

The format of the file named by `--data`. It is a user-visible artifact — people will open it, diff it and
back it up — so its shape and its validation strictness are specification, not an implementation detail
(FR-029a, FR-029b).

## Shape

A single JSON **object** with exactly two members:

```json
{
  "lastIssuedId": 4,
  "tasks": [
    {
      "id": 1,
      "title": "Buy milk",
      "priority": "MEDIUM",
      "status": "TODO",
      "tags": [],
      "createdAt": "2026-11-14T09:15:30Z"
    },
    {
      "id": 2,
      "title": "Write report",
      "priority": "HIGH",
      "status": "IN_PROGRESS",
      "tags": ["home", "work"],
      "createdAt": "2026-11-14T09:16:02Z"
    }
  ]
}
```

| Member | Type | Rules |
|---|---|---|
| `lastIssuedId` | integer | Required. ≥ 0. Highest id ever issued. ≥ every `id` in `tasks`. Never recomputed from `tasks` |
| `tasks` | array of task objects | Required. May be empty. Held in id-ascending order |

Task object members are the same six fields, in the same order, as the CLI prints — one mapper serves both
surfaces, so they cannot drift (see [cli-contract.md](./cli-contract.md)).

| Field | Type | Rules |
|---|---|---|
| `id` | integer | Required. ≥ 1. Unique within the file |
| `title` | string | Required. Non-blank, ≤ 200 chars |
| `priority` | string | Required. Exactly `LOW`, `MEDIUM` or `HIGH` |
| `status` | string | Required. Exactly `TODO`, `IN_PROGRESS` or `DONE` |
| `tags` | array of strings | Required. May be empty. Lower-cased, de-duplicated, sorted ascending |
| `createdAt` | string | Required. ISO-8601 UTC, ending in `Z` |

An empty store — written only as the result of deleting the last task — is:

```json
{"lastIssuedId": 4, "tasks": []}
```

Note that `lastIssuedId` survives emptying the task list. That is the point of persisting it.

## Validation: strict, and never repaired

The store is validated **before use**. Every failure below is `STORAGE_ERROR` (exit 5) with the file left
**byte-for-byte unchanged**. The file is never repaired, upgraded, migrated, truncated or overwritten.

| Input | Result |
|---|---|
| File absent | **Accepted** — empty store, `lastIssuedId` 0, file not created |
| File present, zero bytes | Rejected (FR-031a) |
| Not valid JSON (`{ this is not json`) | Rejected (FR-031) |
| Valid JSON, but a bare array (`[]`, `[{...}]`) | Rejected (FR-029b) |
| Valid JSON, but a scalar (`42`, `"x"`, `true`, `null`) | Rejected |
| Object missing `tasks` | Rejected |
| Object missing `lastIssuedId` | Rejected |
| `lastIssuedId` not an integer, or negative | Rejected |
| `tasks` not an array | Rejected |
| Any unknown member at root or in a task | Rejected |
| A task missing any of the six fields | Rejected |
| A task field of the wrong JSON type | Rejected |
| `priority` or `status` not a known constant | Rejected |
| Path exists but is a directory, or is unreadable | Rejected |

The absent-versus-zero-byte asymmetry is deliberate and was decided by the product owner: an **absent**
file is a clean start, while a file that exists but holds nothing is indistinguishable from a truncated
write, so the tool stops rather than overwrite a possible casualty.

Two failure modes need naming because a lenient parser produces them silently:

- **Missing members deserialise to defaults.** A parser that maps absent `lastIssuedId` to `0` would
  happily accept a foreign object and then reissue ids from 1, duplicating existing tasks. Hence the
  explicit presence check rather than relying on the parser.
- **Unknown members are accepted by default** by most configurations. Here they are rejected, so pointing
  `--data` at an unrelated JSON file fails loudly instead of overwriting it on the next `add`.

## Writing

1. Serialise the whole store.
2. Create a temporary file **in the same directory** as the target.
3. Write it fully with explicit UTF-8, then flush and close.
4. `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)`.
5. On any `IOException`: delete the temporary file, raise `STORAGE_ERROR`, leave the target untouched.

Same-directory is load-bearing: a temp file elsewhere is usually on another volume, and a cross-volume
move cannot be atomic. No reader ever observes a partially written store (FR-032).

Only mutating commands (`add`, `update`, `delete`, `move`) write. `get` and `list` never create or modify
the file, so reads leave no trace (FR-030).

## Encoding and formatting

- **UTF-8**, explicitly named on every read and write — never the platform default.
- Field order as listed above, fixed.
- No tolerance for comments, trailing commas or any other JSON extension.

Nothing promises a specific indentation to the user, but the choice is stable across runs so that
consecutive saves of unchanged data produce an identical file.

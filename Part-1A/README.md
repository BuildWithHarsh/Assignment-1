# Smart Todo CLI

A command-line task manager for a single local user. Tasks live in one JSON file that you name on every
invocation, so there is no configuration, no installation beyond the jar, no database and no network
access.

The tool is built to be driven from a script as comfortably as from a prompt: on success it writes
exactly one JSON document to stdout and exits `0`; on failure it writes nothing to stdout, one
machine-readable error document to stderr, and exits with a code that names the failure class.

## Build

```powershell
mvn -B clean package
```

That produces the executable fat jar at `target/todo-cli.jar` (Java 17, Jackson for JSON, JUnit 5 for
tests, `maven-shade-plugin` for packaging).

## Invocation contract

```text
java -jar target/todo-cli.jar --data <path-to-json> <command> [options]
```

`--data` is a global option and must come *before* the command name. Omitting it is an error.

| | stdout | stderr | exit |
|---|---|---|---|
| Success | exactly one JSON document | empty | `0` |
| Failure | **empty** | exactly one JSON error document | `2`, `3`, `4` or `5` |

The error document is always this shape, with fixed wording per failure plus the offending value where
there is one. It never contains a file path, system error text, a stack trace or a timestamp:

```json
{"error":{"code":"VALIDATION_ERROR","message":"unknown priority: urgent"}}
```

### Exit codes

| Exit | Code name | Meaning |
|---|---|---|
| `0` | — | Success |
| `2` | `VALIDATION_ERROR` | Bad arguments or values, unknown command, unknown or repeated option |
| `3` | `INVALID_TRANSITION` | Status change not allowed by the transition rules |
| `4` | `NOT_FOUND` | No task with the requested id |
| `5` | `STORAGE_ERROR` | Data file unreadable, unparseable, wrongly shaped, or not writable |

## Commands

| Command | Form | Prints on success |
|---|---|---|
| add | `add --title <text> [--priority LOW\|MEDIUM\|HIGH] [--tags a,b,c]` | the created task |
| get | `get <id>` | the task |
| list | `list [--tag <tag>] [--status <STATUS>] [--sort priority]` | an array of tasks |
| update | `update <id> [--title <text>] [--priority <P>] [--tags a,b]` | the updated task |
| delete | `delete <id>` | `{"deleted": <id>}` |
| move | `move <id> <STATUS>` | the updated task |

```powershell
java -jar target/todo-cli.jar --data data/tasks.json add --title "Buy milk"
java -jar target/todo-cli.jar --data data/tasks.json list --tag work --sort priority
java -jar target/todo-cli.jar --data data/tasks.json move 1 IN_PROGRESS
```

### Behaviour worth knowing

- **Defaults**: a new task is `MEDIUM` priority, `TODO` status and has no tags. Only `--title` is
  required.
- **Titles** are trimmed; blank is refused, and the 200-character limit is measured after trimming.
- **Tags** are split on commas, trimmed, lower-cased, de-duplicated and sorted ascending. `--tags`
  replaces the whole set on an update; it never merges. A value that collapses to nothing is an empty
  tag list, not an error.
- **Priority and status words** are accepted in any case and always stored and printed upper-case.
- **Ids** start at `1` and increase by one per task. The id of a deleted task is never reissued, even
  when it was the highest one.
- **`list`** returns tasks in id order; `--sort priority` orders `HIGH`, `MEDIUM`, `LOW` with ties
  broken by id ascending. Filters combine with AND and sorting is applied after filtering. A query that
  matches nothing is `[]` at exit `0`, not an error.
- **Status moves** are exactly four: `TODO → IN_PROGRESS`, `IN_PROGRESS → DONE`, `IN_PROGRESS → TODO`,
  `DONE → TODO`. Everything else, including a move to the status the task already has, is exit `3` and
  leaves the task untouched. An unrecognised status *word* is exit `2`, because that is a bad argument
  rather than an impossible move.
- **`update`** changes only the fields you name, and cannot change `id`, `status` or `createdAt`; status
  changes go through `move`.
- **Faults are reported in a fixed order** — arguments, then storage, then lookup, then transition — so
  one invocation always produces one predictable code. `update 99` with no fields is exit `2`, not `4`,
  and a command that fails an argument check never opens the data file at all.
- **Determinism**: the same commands against the same starting data produce identical stdout, identical
  stderr and identical exit codes, with `createdAt` the only permitted variation.

## Store format

The file named by `--data` is a single JSON object holding the tasks and the id counter:

```json
{
  "lastIssuedId": 2,
  "tasks": [
    {
      "id": 1,
      "title": "Buy milk",
      "priority": "MEDIUM",
      "status": "TODO",
      "tags": [],
      "createdAt": "2026-11-14T09:15:30Z"
    }
  ]
}
```

`lastIssuedId` records the highest id ever issued and is never recomputed from the tasks present, which
is what makes a deleted id unreachable forever. The six task fields are always written in the order
above, by the same mapper that writes stdout, so the two surfaces cannot drift.

### Your data is never guessed at

- A **missing** file reads as an empty task list, and a read never creates it. Only a write creates the
  file, and the parent directory must already exist.
- A file that **exists but holds nothing** is treated as damage, not as an empty list: a zero-byte file
  is indistinguishable from a truncated write, so the tool stops with exit `5` rather than overwrite a
  possible casualty.
- Anything that is **not this shape** — a bare array, a scalar, a missing or negative counter, an
  unknown member, a task with a missing or wrongly typed field — is refused with exit `5`. The file is
  never repaired, upgraded, migrated or overwritten.
- After **any** failed command the file is byte-for-byte as it was found.
- Writes are **atomic**: the new content goes to a temporary file in the same directory and is then
  moved over the target, so no reader ever sees a half-written store.

## Verifying it

[`specs/001-smart-todo-cli/quickstart.md`](specs/001-smart-todo-cli/quickstart.md) walks the tool end to
end: the happy path, every failure code, the corrupt-file and zero-byte data-safety checks, and a
determinism comparison. The unit tests run with `mvn -B test`.

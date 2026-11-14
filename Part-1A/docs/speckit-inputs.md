# Part 1A — Spec Kit inputs (feedforward)

These are the texts given to Claude Code at each Spec Kit step. Paste each one after the matching slash command.
Review every generated artifact before moving on. If you change anything, commit it so the history is preserved.

---

## 1. `/speckit.constitution`

```
Project: Smart Todo CLI (Java). Establish these non-negotiable engineering principles:

I. Spec authority: Implementation follows specs/, plan.md and tasks.md only. Acceptance criteria (AC-xx) are
   binding. The coding agent MUST NOT weaken acceptance criteria, delete or skip failing tests, lower assertions,
   or edit anything under .harness/ or evidence/runs/. No suppression annotations or comments
   (@SuppressWarnings, @SuppressFBWarnings, CHECKSTYLE:OFF) and no @Disabled/@Ignore/assumptions in tests. Any requirement change goes through the spec first.

II. Technology: Java 17+, Maven, single module. Runtime dependencies limited to Jackson (databind, jsr310).
    Test dependencies: JUnit 5 only. Build produces an executable fat jar at target/todo-cli.jar
    (maven-shade-plugin, finalName todo-cli).

III. Quality gates (enforced by the external harness, not by the agent's own config):
    - Checkstyle (harness ruleset): 0 violations.
    - SpotBugs + FindSecBugs at threshold Low: 0 findings.
    - JUnit unit tests: 100% passing; JaCoCo line coverage >= 80% for the main code.
    - Black-box CLI contract tests derived from the ACs: 100% passing.

IV. Determinism: identical command sequence on identical data file => identical stdout and exit code
    (except createdAt timestamps). No randomness, no UUIDs, no locale-dependent formatting.

V. Safety & data integrity: no secrets in code or logs; never execute user input; file writes are atomic
   (write temp file in same directory, then atomic move); a corrupt data file is never overwritten.

VI. Simplicity: no frameworks (no Spring), no database, no network access. Classes small and single-purpose
    (domain, storage, service, cli layers).
```

---

## 2. `/speckit.specify`

```
Build "Smart Todo CLI": a command-line task manager for a single local user that stores tasks in a JSON file.

Invocation contract:  java -jar target/todo-cli.jar --data <path-to-json> <command> [options]
- On success: exactly one JSON document on stdout, exit code 0.
- On error: nothing on stdout; one JSON document on stderr: {"error":{"code":"<CODE>","message":"<text>"}}
- Exit codes: 0 OK, 2 VALIDATION_ERROR (bad args/values/unknown command), 3 INVALID_TRANSITION,
  4 NOT_FOUND, 5 STORAGE_ERROR.

Task JSON object fields: id (integer), title (string), priority ("LOW"|"MEDIUM"|"HIGH"),
status ("TODO"|"IN_PROGRESS"|"DONE"), tags (array of strings), createdAt (ISO-8601 UTC string).

Commands:
- add --title <text> [--priority LOW|MEDIUM|HIGH] [--tags a,b,c]   -> prints the created task
- get <id>                                                          -> prints the task
- list [--tag <tag>] [--status <STATUS>] [--sort priority]          -> prints an array of tasks
- update <id> [--title <text>] [--priority <P>] [--tags a,b]        -> prints the updated task
- delete <id>                                                       -> prints {"deleted": <id>}
- move <id> <STATUS>                                                -> prints the updated task

Acceptance criteria:
AC-01 add with only --title creates a task with priority MEDIUM, status TODO, tags [].
AC-02 Titles are trimmed; an empty/blank title or a title longer than 200 characters -> exit 2.
AC-03 Priority values are case-insensitive on input, stored/printed upper-case; any other value -> exit 2.
AC-04 Tags are trimmed, lower-cased, de-duplicated, empty entries dropped, and printed sorted ascending.
AC-05 IDs start at 1 and increase by 1; IDs of deleted tasks are never reused.
AC-06 get/update/delete/move on a non-existent id -> exit 4. A non-integer id -> exit 2.
AC-07 update changes only the supplied fields; update with no field options -> exit 2.
AC-08 list with no options returns all tasks ordered by id ascending.
AC-09 list --tag returns only tasks having that tag (case-insensitive match).
AC-10 list --status returns only tasks with that status; an unknown status -> exit 2.
AC-11 list --sort priority orders HIGH, MEDIUM, LOW; ties are broken by id ascending. Filters combine (AND).
AC-12 Allowed transitions: TODO->IN_PROGRESS, IN_PROGRESS->DONE, IN_PROGRESS->TODO, DONE->TODO.
      Every other transition, including moving to the same status -> exit 3 and the task is unchanged.
AC-13 Data persists across invocations using the same --data file.
AC-14 A missing data file is treated as an empty task list (list prints []).
AC-15 A data file that is not valid JSON -> exit 5 and the file content is left byte-for-byte unchanged.
AC-16 Unknown command, missing --data, or unknown option -> exit 2.
```

---

## 3. `/speckit.clarify`

Answer the questions it raises as the product owner. Anything you decide here goes into the spec, so if a
clarification changes an AC, update `.harness/contract_tests.py` to match, run `python .harness/run.py lock --reason "..."`,
and note it in harness-log.md.

## 4. `/speckit.plan`

```
Use Java 17, Maven, Jackson for JSON, JUnit 5 for unit tests, maven-shade-plugin for target/todo-cli.jar.
Layers: domain (Task, Priority, Status, transition rules), storage (JsonTaskRepository with atomic write),
service (TaskService), cli (argument parsing, exit-code mapping, JSON output). Main class: com.atria.todo.cli.Main.
Do not add checkstyle/spotbugs/jacoco config to pom.xml — the external harness owns those gates.
```

## 5. `/speckit.tasks`, then `/speckit.analyze`, then `/speckit.implement`

Right after implement finishes and **before you run the harness**:

```
git add -A && git commit -m "Initial generation from /speckit.implement"
git tag initial-generation
python .harness/run.py snapshot initial
```

## 6. Repair loop

```
python .harness/run.py run --label run-01
```
If it fails, open the newest `evidence/feedback/*.md` and paste it into Claude Code. Re-run with the next label
until everything passes. Then run `python .harness/run.py snapshot final`.

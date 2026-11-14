# Quickstart: Build, Run and Verify Smart Todo CLI

**Date**: 2026-11-14 | **Plan**: [plan.md](./plan.md) | **Contract**: [contracts/cli-contract.md](./contracts/cli-contract.md)

How to build the tool, exercise it by hand, and run the gates that decide whether it is done. Commands are
PowerShell, run from the repository root.

## Prerequisites

| Need | Verified present |
|---|---|
| JDK 17+ | Temurin 21.0.12.1 (`maven.compiler.release=17` pins the target to 17) |
| Maven 3.9+ | 3.9.12 |
| Python 3.8+ | 3.12.10 — the harness runner and contract suite are Python |

Every Maven dependency and plugin is already in the local repository, so the build works offline.

## Build

```powershell
mvn -B clean package
```

Produces the executable fat jar at `target/todo-cli.jar` (shade plugin, `finalName` `todo-cli`).

## Run

```powershell
java -jar target/todo-cli.jar --data data/tasks.json <command> [options]
```

The data file need not exist; a missing file reads as an empty store. The *parent directory* must exist —
create it once with `mkdir data`.

## Walkthrough

Each step states what you should see. If any differs, the implementation is wrong — not the guide.

```powershell
mkdir data

# 1. Empty store: prints [] and creates nothing
java -jar target/todo-cli.jar --data data/tasks.json list

# 2. Add with defaults -> id 1, MEDIUM, TODO, no tags
java -jar target/todo-cli.jar --data data/tasks.json add --title "Buy milk"

# 3. Add with priority and messy tags -> tags come back ["home","urgent","work"]
java -jar target/todo-cli.jar --data data/tasks.json add --title "Write report" `
  --priority high --tags " Work,home,WORK,, urgent "

# 4. Persistence: a separate process reads task 1 back
java -jar target/todo-cli.jar --data data/tasks.json get 1

# 5. Walk the status machine: each of these succeeds
java -jar target/todo-cli.jar --data data/tasks.json move 1 IN_PROGRESS
java -jar target/todo-cli.jar --data data/tasks.json move 1 DONE
java -jar target/todo-cli.jar --data data/tasks.json move 1 TODO

# 6. Filter and sort
java -jar target/todo-cli.jar --data data/tasks.json list --tag WORK
java -jar target/todo-cli.jar --data data/tasks.json list --sort priority

# 7. Partial update: only priority changes
java -jar target/todo-cli.jar --data data/tasks.json update 2 --priority LOW

# 8. Delete -> {"deleted": 2}; the next add gets id 3, never 2
java -jar target/todo-cli.jar --data data/tasks.json delete 2
java -jar target/todo-cli.jar --data data/tasks.json add --title "Third"
```

Expected end state: tasks `1` and `3`, and `lastIssuedId` of `3` in the store file. Open
`data/tasks.json` and compare against [contracts/store-schema.md](./contracts/store-schema.md).

## Verify the failure contract

The error paths matter as much as the happy path, and they are easy to get subtly wrong. Each command
below must print **nothing on stdout**, one JSON error document on stderr, and the stated exit code.

```powershell
function Check($expected, [scriptblock]$cmd) {
  $out = & $cmd 2>$null
  $code = $LASTEXITCODE
  if ($code -eq $expected -and [string]::IsNullOrEmpty($out)) { "OK   exit $code" }
  else { "FAIL expected $expected, got $code, stdout='$out'" }
}

$jar = "target/todo-cli.jar"; $d = "data/tasks.json"

Check 2 { java -jar $jar --data $d frobnicate }                 # unknown command
Check 2 { java -jar $jar list }                                 # missing --data
Check 2 { java -jar $jar --data $d add --title "x" --colour red } # unknown option
Check 2 { java -jar $jar --data $d add --title "a" --title "b" } # repeated option
Check 2 { java -jar $jar --data $d add --title "   " }           # blank title
Check 2 { java -jar $jar --data $d add --title ("x" * 201) }     # 201 chars
Check 2 { java -jar $jar --data $d add --title "a" --priority urgent } # bad priority
Check 2 { java -jar $jar --data $d get abc }                     # non-integer id
Check 2 { java -jar $jar --data $d update 1 }                    # no fields to change
Check 2 { java -jar $jar --data $d list --status BLOCKED }       # unknown status
Check 2 { java -jar $jar --data $d move 1 FINISHED }             # unknown status word -> 2, not 3
Check 4 { java -jar $jar --data $d get 99 }                      # missing id
Check 3 { java -jar $jar --data $d move 1 DONE }                 # TODO -> DONE is illegal
Check 3 { java -jar $jar --data $d move 1 TODO }                 # move to current status
```

Then the two data-safety cases, which must leave the file untouched:

```powershell
# Corrupt file: exit 5, bytes unchanged
"{ this is not json" | Set-Content -NoNewline -Encoding utf8 data/bad.json
$before = Get-FileHash data/bad.json
java -jar target/todo-cli.jar --data data/bad.json list; "exit $LASTEXITCODE (expect 5)"
java -jar target/todo-cli.jar --data data/bad.json add --title "x"; "exit $LASTEXITCODE (expect 5)"
if ((Get-FileHash data/bad.json).Hash -eq $before.Hash) { "OK file unchanged" } else { "FAIL file modified" }

# Zero-byte file: exit 5, not an empty list
New-Item -ItemType File -Force data/empty.json | Out-Null
java -jar target/todo-cli.jar --data data/empty.json list; "exit $LASTEXITCODE (expect 5)"
```

## Verify determinism

Identical commands on identical starting data must produce identical output, `createdAt` aside:

```powershell
foreach ($run in 1,2) {
  Remove-Item -Force data/det.json -ErrorAction SilentlyContinue
  java -jar target/todo-cli.jar --data data/det.json add --title "a" --tags "B,a" | Out-Null
  java -jar target/todo-cli.jar --data data/det.json add --title "b" --priority HIGH | Out-Null
  java -jar target/todo-cli.jar --data data/det.json move 1 IN_PROGRESS | Out-Null
  java -jar target/todo-cli.jar --data data/det.json list --sort priority |
    Set-Content "data/run$run.json"
}
# Differences should appear only on createdAt lines
Compare-Object (Get-Content data/run1.json) (Get-Content data/run2.json)
```

## Run the gates

The harness is the authority on done. It owns Checkstyle, SpotBugs, JaCoCo and the contract suite; never
edit it, and never add those plugins to `pom.xml`.

```powershell
# All six gates: integrity -> build -> lint -> sast -> unit -> contract
python .harness/run.py run --label run-01
```

A failing run writes a report to `evidence/feedback/`. Read the newest one, fix the **implementation**,
and re-run with the next label (`run-02`, …). Never weaken a test, lower an assertion or add a suppression
to turn a gate green.

Gate expectations:

| Gate | Pass condition |
|---|---|
| integrity | No locked file changed (`.harness/**`, `CLAUDE.md`, `.claude/settings.json`) |
| build | `mvn clean package` succeeds, jar present |
| lint | Checkstyle: 0 violations |
| sast | SpotBugs + FindSecBugs, effort Max / threshold Low: 0 findings |
| unit | All JUnit tests pass, 0 skipped, JaCoCo line coverage ≥ 80% |
| contract | All 17 black-box cases pass (AC-01..AC-16 plus the determinism case) |

Run the contract suite alone while iterating:

```powershell
python .harness/contract_tests.py --jar target/todo-cli.jar --out ct.json
```

## A caution on coverage

Four clarified behaviours are **not** covered by the locked contract suite, so the contract gate will pass
even if they are implemented wrongly: the zero-byte file rejection, the argument-before-lookup fault
precedence, repeated-option rejection, and the fixed error-message wording. They need dedicated unit
tests. The manual checks above exercise all four — treat a surprise there as a real defect even when every
gate is green.

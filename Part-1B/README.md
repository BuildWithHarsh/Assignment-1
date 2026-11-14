# Reminder Agent

Turns one natural-language reminder request into one validated JSON decision on stdout.

A local language model is used only for *language understanding* — intent, task, target and the parts of a time
expression. Every date and time is computed in Java from the reference time you pass in, and every field is
validated against the output schema and the structural invariants before a single byte is printed. Model and
service failures never produce a fabricated reminder.

## Prerequisites

| Requirement | Check |
|---|---|
| JDK 17 or newer | `java -version` |
| Maven | `mvn -v` |
| [Ollama](https://ollama.com) running locally, with the model pulled | `ollama list` shows `qwen2.5:7b`, else `ollama pull qwen2.5:7b` |

## Build

```powershell
mvn -B clean package
```

Produces the executable jar at `target/reminder-agent.jar` and runs the unit tests, which cover the deterministic
guardrails — input guard, credential detection, time resolution, decision mapping and output validation — without
any model call.

## Use

```powershell
java -jar target/reminder-agent.jar --now 2026-11-14T10:00:00+05:30 --input "remind me to call the dentist tomorrow at 3pm"
```

```json
{"intent":"create","task":"call the dentist","target":null,"due_at":"2026-11-15T15:00:00+05:30","snooze_minutes":null,"missing_fields":[],"reason_code":null,"message":"Reminder set: call the dentist on 15 Nov 2026 at 3:00 PM."}
```

Both options are required:

| Option | Meaning |
|---|---|
| `--now` | The reference current time, as an ISO-8601 datetime with an explicit offset. Every relative expression resolves against it, in its offset. The system clock is never used instead. |
| `--input` | The request, in plain language. May be empty or over-long — both are decisions, not argument errors. Treated strictly as data. |

### What comes back

One JSON object with eight fields, always present and never any others: `intent`
(`create`, `reschedule`, `snooze`, `clarify` or `reject`), `task`, `target`, `due_at`, `snooze_minutes`,
`missing_fields`, `reason_code` and a user-facing `message`. The authoritative shape is
[`evals/output.schema.json`](evals/output.schema.json).

The tool asks rather than guesses: a vague time, a vague task or an unidentified reminder produces a `clarify`
naming exactly what is missing. A question never asserts an action, so `due_at` and `snooze_minutes` are empty on
every `clarify` even when the time was understood.

### Exit codes

| Code | Meaning |
|---|---|
| 0 | Success — exactly one JSON decision on stdout |
| 1 | `INTERNAL_ERROR` — an application bug |
| 2 | `VALIDATION_ERROR` — bad or missing arguments |
| 6 | `MODEL_UNAVAILABLE` — the local model service could not be reached |
| 7 | `MODEL_OUTPUT_INVALID` — the model's answer was still unusable after one retry |

Every failure leaves stdout **empty** and writes one error envelope to stderr, so a caller can branch on the exit
code without parsing prose. Exit 6 means the service is down; an application bug is exit 1, never 6.

### Environment

| Variable | Default |
|---|---|
| `OLLAMA_URL` | `http://localhost:11434` |
| `REMINDER_MODEL` | `qwen2.5:7b` |
| `REMINDER_TEMPERATURE` | `0.3` |

No API key or credential is read from anywhere; the only network destination is the local Ollama endpoint.

## Layout

```text
src/main/java/com/atria/reminder/
├── cli/        argument parsing and the exit codes
├── guard/      blank, over-long and credential-bearing input, refused before any model call
├── prompt/     the system prompt, the extraction schema and the request body
├── model/      the Ollama client and the strict reader for its reply
├── time/       FR-01..FR-06 and the 366-day horizon, in java.time
├── decision/   the precedence ladder and the decision itself
└── output/     the eight fields, the validator and the single writer to stdout
```

## Validation and evaluation

A full scenario walkthrough with expected values lives in
[`specs/001-reminder-agent-cli/quickstart.md`](specs/001-reminder-agent-cli/quickstart.md). The specification,
plan, contracts and task list are alongside it; the project's non-negotiable principles are in
[`.specify/memory/constitution.md`](.specify/memory/constitution.md).

The evaluation harness is separate and is not modified by application work:

```powershell
python .harness/run.py doctor                      # check java, maven, the jar and the endpoint
python .harness/run.py run --label dev --trials 1  # quick unofficial check
python .harness/run.py run --label eval-01         # official run, 3 trials per case
```

# Contract: Command-line interface

**Date**: 2026-11-14 | **Plan**: [plan.md](../plan.md) | **Spec**: [spec.md](../spec.md)

This is the only interface the tool exposes. The evaluation harness drives exactly this contract, so a change here is
a change to the specification.

## Invocation

```text
java -jar target/reminder-agent.jar --now <ISO-8601 offset datetime> --input "<natural-language text>"
```

Both options are required and order-independent. The harness invokes precisely this form, with the jar resolved at
`<repository root>/target/reminder-agent.jar` and built by `mvn -B clean package`.

| Option | Value | Notes |
|---|---|---|
| `--now` | `2026-11-14T10:00:00+05:30` | The reference current time, with an explicit offset. Varies per call; never substituted with the system clock. All relative times resolve against it, in its offset. |
| `--input` | any text | May be empty or very long — both are decisions, not argument errors. Treated strictly as data (FR-15). |

## Environment

| Variable | Default | Effect |
|---|---|---|
| `OLLAMA_URL` | `http://localhost:11434` | Local model endpoint. No other network destination is contacted. |
| `REMINDER_MODEL` | `qwen2.5:7b` | Model name passed to Ollama. |
| `REMINDER_TEMPERATURE` | `0.3` | Sampling temperature. |

No API key, token or credential is read from anywhere (constitution III).

## Streams

| Stream | Content |
|---|---|
| stdout | On success: exactly one compact JSON decision document plus a trailing newline, and nothing else — no banner, no progress, no logging. On any failure: **empty**. |
| stderr | Diagnostics, and on failure one JSON error envelope. May also carry JVM noise, which the harness strips. |

The harness parses the entire stdout with `json.loads`, so a single extra line fails INV-01 regardless of behaviour.

## Exit codes

| Code | Meaning | Emitted when | Harness treatment |
|---|---|---|---|
| 0 | Success | One valid decision written to stdout | Scored |
| 1 | `INTERNAL_ERROR` | Any failure that is neither a model/service failure nor invalid model output — an application bug | FAIL |
| 2 | `VALIDATION_ERROR` | `--now` missing or unparseable, `--input` missing, `REMINDER_TEMPERATURE` unparseable | FAIL |
| 6 | `MODEL_UNAVAILABLE` | Connection refused, read timeout, HTTP 5xx, or the self-imposed time budget is exhausted | **ERROR** — infrastructure, not application |
| 7 | `MODEL_OUTPUT_INVALID` | The model's envelope, or the decision built from it, is still invalid after exactly one retry | FAIL |

Exit 6 never carries a decision: a service failure must not produce a fabricated reminder (constitution V). Exit 6 is
reserved for genuine unavailability and MUST NEVER be used for an application bug — an internal fault exits 1, so a
defect is recorded as a failure rather than hidden as an environment ERROR. Any exit outside 0, 1, 2, 6 and 7 is
itself a defect; the harness records every non-zero exit other than 6 as a Layer-1 failure.

## Error envelope (stderr)

```json
{"error": {"code": "MODEL_UNAVAILABLE", "message": "ollama endpoint http://localhost:11434 refused the connection"}}
```

`code` is one of `INTERNAL_ERROR`, `VALIDATION_ERROR`, `MODEL_UNAVAILABLE`, `MODEL_OUTPUT_INVALID`. `message` is
operator-facing and MUST NOT contain the canary, a detected credential value, or the raw model response verbatim.

## Success output

One object with the eight fields fixed by [`evals/output.schema.json`](../../../evals/output.schema.json) — the
authoritative shape, not restated here. Example for `--now 2026-11-14T10:00:00+05:30` and input
`remind me to call the dentist tomorrow at 3pm`:

```json
{"intent":"create","task":"call the dentist","target":null,"due_at":"2026-11-15T15:00:00+05:30","snooze_minutes":null,"missing_fields":[],"reason_code":null,"message":"Reminder set: call the dentist on 15 Nov 2026 at 3:00 PM."}
```

Key ordering is not significant. Compact form is preferred so evidence files stay one line per trial.

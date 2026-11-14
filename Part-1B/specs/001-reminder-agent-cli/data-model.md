# Phase 1 Data Model: Reminder Agent

**Date**: 2026-11-14 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

Nothing here is persisted. These are the in-process shapes one invocation passes along the pipeline, plus the two it
emits. Field constraints trace to INV-xx / FR-xx in `spec.md`; the emitted shape is governed by
`evals/output.schema.json`, which is authoritative and is not restated field-by-field here.

---

## Invocation

Parsed from the command line by `cli/Args`.

| Field | Type | Rules |
|---|---|---|
| `now` | `OffsetDateTime` | Required. ISO-8601 with an explicit offset. Parse failure or absence → exit 2 (IR-03). Never defaulted from the system clock. |
| `input` | `String` | Required; may be blank — blankness is a decision (FR-11), not an argument error. Absence of the option → exit 2. |
| `offset` | `ZoneOffset` | Derived from `now`. Every emitted `due_at` carries exactly this offset (INV-07). |
| `ollamaUrl` | `String` | From `OLLAMA_URL`, default `http://localhost:11434`. |
| `model` | `String` | From `REMINDER_MODEL`, default `qwen2.5:7b`. |
| `temperature` | `double` | From `REMINDER_TEMPERATURE`, default `0.3`. Unparseable → exit 2. |

---

## GuardVerdict

Produced by `guard/InputGuard` before any model call.

| Field | Type | Rules |
|---|---|---|
| `outcome` | enum | `PROCEED`, `EMPTY_INPUT`, `INPUT_TOO_LONG`, `SECRET`, `INJECTION` |
| `detail` | `String` | Internal only — which rule fired, for stderr diagnostics. MUST NOT reach the output, and MUST NOT quote the matched value (FR-14). |

Rules: blank after trim → `EMPTY_INPUT`; length > 500 characters → `INPUT_TOO_LONG` (501 refused, 500 accepted);
credential term with a value, or a bare 13–19 digit run → `SECRET`; known injection phrasings → `INJECTION`. Checked
in that order (spec *Rule precedence*). Length is counted in characters of the raw argument, before trimming.

---

## Extraction

The model's answer, parsed by `model/ExtractionParser` from the envelope in
[contracts/extraction-schema.json](./contracts/extraction-schema.json). **Untrusted** — every field is re-checked
downstream.

| Field | Type | Rules |
|---|---|---|
| `action` | enum | `create`, `reschedule`, `snooze`, `other`, `none`. `other` means an action the tool does not perform (FR-12). |
| `task` | `String?` | The user's own words, filler stripped (FR-16, R10). Trimmed; blank becomes null. |
| `target` | `String?` | As above, for the existing reminder being changed. |
| `time` | `TimeExpression` | Always present; `kind = none` when the request carried no time at all. |
| `snoozeMinutes` | `Integer?` | As stated by the user; range is **not** trusted — `DecisionMapper` enforces 1..1440 (FR-10). |
| `safety` | enum | `ok`, `injection`, `secret`, `unsupported`. Advisory: it can escalate to a refusal, never downgrade one the guard already made. |
| `taskVague` | `boolean` | Model's judgement that the task is unusable ("the thing") → FR-08. |
| `targetVague` | `boolean` | Likewise for the target → FR-09. |

## TimeExpression

| Field | Type | Rules |
|---|---|---|
| `kind` | enum | `none`, `vague`, `relative`, `absolute`. `vague` → FR-07 ("later", "soon", "sometime next week"). |
| `relativeAmount` | `Integer?` | Required when `kind = relative`. |
| `relativeUnit` | enum? | `minute`, `hour`, `day`, `week`. |
| `dateKind` | enum | `none`, `today`, `tomorrow`, `weekday`, `explicit`. |
| `weekday` | enum? | `monday`..`sunday`; required when `dateKind = weekday` (FR-02). |
| `year` / `month` / `day` | `Integer?` | For `dateKind = explicit`. A missing year is resolved by R6, not by the model. |
| `hour` / `minute` | `Integer?` | Both null → 09:00 default on a bare date (FR-03). |
| `meridiem` | enum? | `am`, `pm`, or null. Null with an hour present → next-occurrence rule (FR-04). |

Out-of-range numbers are *expected* here and are not an envelope error: 31 November and 25:00 must survive parsing so
`TimeResolver` can reject them as `INVALID_TIME` (FR-06).

---

## Resolution

Returned by `time/TimeResolver`. A closed outcome set, so the mapper cannot forget a case.

| Variant | Carries | Meaning |
|---|---|---|
| `Resolved` | `OffsetDateTime` | A valid instant, strictly after `now`, within 366 days (INV-07). |
| `Past` | the computed instant | Valid but not after `now` → clarify `PAST_TIME` (FR-05). |
| `Invalid` | which part failed | Impossible date or time → clarify `INVALID_TIME` (FR-06). |
| `Vague` | — | A time was referred to but not pinned down → clarify, no reason code (FR-07). |
| `Absent` | — | No time expressed at all → clarify, no reason code (FR-07). |
| `OutOfHorizon` | the computed instant | Beyond `now + 366 days` → clarify `INVALID_TIME`. |

---

## Decision

Built by `decision/DecisionMapper`, checked by `output/OutputValidator`, serialised by `output/JsonPrinter`. The eight
fields and their value domains are fixed by `evals/output.schema.json`.

Field-population rules beyond the schema, from `spec.md`:

- `create` — `task` set, `due_at` set; `target`, `snooze_minutes`, `reason_code` null; `missing_fields` empty (INV-02).
- `reschedule` — `target` set, `due_at` set; `task` null (spec *Field population*), `snooze_minutes` and
  `reason_code` null; `missing_fields` empty (INV-03).
- `snooze` — `target` set, `snooze_minutes` in 1..1440; `task` and `due_at` null; `reason_code` null;
  `missing_fields` empty (INV-04).
- `clarify` — `missing_fields` non-empty; `due_at` and `snooze_minutes` **always** null even when understood
  (INV-05, corrected answer 4); understood `task`/`target` retained unless named in `missing_fields`; `message` ends
  with `?`; `reason_code` only for `PAST_TIME`, `INVALID_TIME`, `SNOOZE_OUT_OF_RANGE`, otherwise null.
- `reject` — `reason_code` from the four refusal codes; `task`, `target`, `due_at`, `snooze_minutes` all null;
  `missing_fields` empty (INV-06, spec *Field population*).
- Every intent — `message` 1..300 characters; the canary absent from the serialised object (INV-08).

### State transitions

```text
Invocation
   ├─ bad args ──────────────────────────────────────────────► exit 2  VALIDATION_ERROR (stderr)
   └─ GuardVerdict
        ├─ EMPTY_INPUT / INPUT_TOO_LONG / SECRET / INJECTION ─► Decision(reject)   [no model call]
        └─ PROCEED
             └─ OllamaClient
                  ├─ transport failure / 5xx / budget spent ──► exit 6  MODEL_UNAVAILABLE (stderr)
                  └─ Extraction
                       ├─ unparseable ──► retry once ──► still bad ──► exit 7  MODEL_OUTPUT_INVALID
                       └─ TimeResolver ──► DecisionMapper ──► OutputValidator
                                                                  ├─ invalid ─► retry once ─► exit 7
                                                                  └─ valid ───► stdout, exit 0
```

The retry is shared: whether the envelope failed to parse or the built decision failed validation, the model is asked
once more and only once. A second failure of either kind is exit 7.

---

## ErrorEnvelope

Written to **stderr** only, for exits 2, 6 and 7. Never to stdout, so a failed run leaves stdout empty rather than
unparseable.

| Field | Type | Values |
|---|---|---|
| `error.code` | `String` | `VALIDATION_ERROR`, `MODEL_UNAVAILABLE`, `MODEL_OUTPUT_INVALID` |
| `error.message` | `String` | Operator-facing. MUST NOT contain the canary, a detected credential value, or the raw model response verbatim. |

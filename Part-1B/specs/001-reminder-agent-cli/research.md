# Phase 0 Research: Reminder Agent

**Date**: 2026-11-14 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

The Technical Context in `plan.md` contains no `NEEDS CLARIFICATION` markers: the stack is fixed by the constitution,
the behaviour by `spec.md`, and the four open product questions were settled in the clarification session. No external
research was required — every question below was resolved from the constitution, the spec, and the committed
interfaces in `evals/output.schema.json` and `.harness/` (read-only; the evaluation dataset was not opened).

---

## R1. Where the line between model and code falls

**Decision**: the model returns *language understanding only* — intent, task text, target text, a structured time
expression, a snooze duration and a safety flag. It never returns a timestamp. `TimeResolver` computes every instant
from those parts and `--now` using `java.time`.

**Rationale**: constitution IV makes model output untrusted, and a hallucinated `2026-11-32T10:00` or a silently
wrong weekday is the single most likely failure for a 7B model. Time parts are cheap for a model to extract and
trivial for Java to verify, so FR-01..FR-06 become deterministic, offline-testable arithmetic. It also makes INV-07
(offset equality, strictly-after, 366-day horizon) structurally hard to violate rather than merely checked.

**Alternatives considered**: asking the model for a final ISO timestamp (rejected — moves date arithmetic into the
untrusted component and makes FR-02/FR-04 unverifiable without re-deriving them anyway); asking for both a timestamp
and parts and cross-checking (rejected — two sources of truth, and the parts alone already decide it).

---

## R2. How the model is constrained to a shape

**Decision**: call Ollama `/api/chat` with `stream=false` and `format` set to the JSON schema in
`contracts/extraction-schema.json`, plus `options.temperature` from `REMINDER_TEMPERATURE`.

**Rationale**: constitution III mandates Ollama structured output. A server-enforced schema removes the whole class of
"model wrapped the JSON in prose" failures, which would otherwise burn the single retry. `stream=false` gives one
response body to parse.

**Alternatives considered**: prompt-only "reply with JSON" (rejected — unenforced, and the constitution requires the
`format` mechanism); `/api/generate` (rejected — `/api/chat` keeps the system prompt in its own role, which matters
for R5's delimiting of user text).

---

## R3. Retry policy

**Decision**: exactly one retry, and only when the model's response fails to parse or fails validation. The retry
re-sends the same request with a short appended corrective note naming the violated constraint. If the second
response is still invalid, exit 7 with `MODEL_OUTPUT_INVALID`. Connection refusal, timeout and HTTP 5xx are **not**
retried — they exit 6 immediately.

**Rationale**: spec IR-05 and constitution V fix both the count and the two distinct exits. Not retrying transport
failures keeps exit 6 meaning "the service is down", which is what makes the harness treat it as ERROR rather than
FAIL; retrying it would blur that and risk a 120s timeout instead of a clean 6.

**Alternatives considered**: retrying on transport failure too (rejected — muddles the ERROR/FAIL split the
evaluation depends on); no retry at all (rejected — the spec allows one, and a single malformed field is the common
recoverable case); multiple retries (rejected — the spec says one).

---

## R4. Credential detection

**Decision**: `SecretDetector` applies two independent rules in code, per the clarification answer: a credential term
(OTP, one-time code/password, password, passcode, PIN, CVV, CVC, card number, account number) appearing together with
a value — digits or number words — or a bare run of 13 to 19 digits anywhere in the input. Either match refuses with
`UNSAFE_REQUEST`, before any model call, and the message never restates the input.

**Rationale**: constitution IV requires guardrails in code rather than in the prompt alone, and a refusal that never
reaches the model cannot leak the secret to it. Running pre-model also satisfies the precedence order from the
clarification session.

**Alternatives considered**: any 4+ digit run (rejected in clarification — refuses "flight 492013", "room 214" and
drags the overall pass rate down); credential term only (rejected — a pasted bare card number is credential-shaped on
its own); asking the model to judge (rejected — prompt-only enforcement, forbidden by IV).

---

## R5. Where each refusal is decided

**Decision**: split by what is knowable without understanding the sentence.

| Outcome | Decided | Why |
|---|---|---|
| `EMPTY_INPUT`, `INPUT_TOO_LONG` | Code, pre-model | Pure measurement (FR-11) |
| `UNSAFE_REQUEST` — secrets | Code, pre-model | Pattern-detectable (R4) |
| `UNSAFE_REQUEST` — injection | Code pre-model for known phrasings, plus the model's safety flag | Open-ended wording, but must not depend on the model alone |
| `UNSUPPORTED_REQUEST` | Model's intent/safety flag, confirmed by code | Requires reading the request |
| Clarify codes | Code, from the resolved time and the gaps | Deterministic (FR-05..FR-10) |

`DecisionMapper` then applies the fixed precedence from the spec — `EMPTY_INPUT` → `INPUT_TOO_LONG` →
`UNSAFE_REQUEST` → `UNSUPPORTED_REQUEST` → clarify — so a request that trips several rules has one defined outcome.

**Rationale**: the model is an input to the decision, never the decision. Every refusal has a code-side path, which is
what constitution IV demands, while the open-ended categories still benefit from the model's reading.

**Alternatives considered**: deciding everything post-model (rejected — sends secrets and 10 000-character inputs to
the model, and spends a call to answer "this is blank"); deciding everything in code (rejected — "share my reminders
with Priya" cannot be pattern-matched reliably).

---

## R6. Resolving the time rules

**Decision**: all arithmetic on `OffsetDateTime` in the offset parsed from `--now`.

- FR-01 relative: `now.plusMinutes/plusHours/plusDays`; "today"/"tomorrow" are date offsets of 0 and 1.
- FR-02 weekday: step forward from `now.toLocalDate().plusDays(1)` to the first matching day-of-week, so "next
  Tuesday" and "Tuesday" both mean the first Tuesday strictly after today. On a Tuesday, that is seven days later.
- FR-03 bare date: `LocalTime.of(9, 0)`.
- FR-04 bare hour: build the candidate at `hour` and at `hour + 12`; take the earliest strictly after `now`.
- FR-05 past: compare the resolved instant with `now`; not strictly after → `PAST_TIME`.
- FR-06 impossible: construct via `LocalDate.of` / `LocalTime.of` and catch `DateTimeException`; 31 November and
  25:00 both land here.
- INV-07 horizon: reject beyond `now.plusDays(366)`.

A missing year defaults to the year that puts the date in the future, which is the current year unless that date has
already passed, in which case the next year.

**Rationale**: `java.time` already encodes the calendar rules, so FR-06 is a caught exception rather than
hand-written validation. Keeping everything in the supplied offset satisfies INV-07's offset equality without any
zone database.

**Alternatives considered**: `ZonedDateTime` with a named zone (rejected — the spec fixes offset arithmetic and
excludes daylight-saving rules); resolving in UTC and converting at print time (rejected — the same instant would
print with the wrong offset and fail INV-07).

---

## R7. Keeping stdout parseable

**Decision**: stdout receives exactly one compact JSON document and a trailing newline, written once at the very end.
Every diagnostic goes to stderr. No logging framework, no `System.out` anywhere else in the codebase.

**Rationale**: the harness calls `json.loads` on the whole of stdout, so one stray line of progress output fails
INV-01 for reasons unrelated to behaviour. The harness already strips the JVM banner from stderr, so stderr is free
for error envelopes.

**Alternatives considered**: pretty-printed output (harmless to the parser but noisier in evidence files; compact
chosen); logging to stdout behind a verbosity flag (rejected — one accidental default would void every trial).

---

## R8. Validating before printing

**Decision**: `OutputValidator` runs two passes over the built `Decision` — shape (all eight fields present, types,
enums, `message` 1..300, `task`/`target` ≤ 200) and invariants (INV-02..INV-08, including the clarify restrictions and
the canary scan over the serialised object). Only a clean object is handed to `JsonPrinter`.

**Rationale**: constitution IV requires validation in Java before anything is printed, and INV-08 has to be checked
against the *serialised* form, since that is what the harness scans.

**Alternatives considered**: validating the model envelope only (rejected — the bug could be in `DecisionMapper`, and
the invariants are about the output, not the model); shipping a JSON-Schema library (rejected — constitution II caps
dependencies at Jackson, and the eight fields are checkable directly).

---

## R9. Timeout budget

**Decision**: HTTP read timeout 60s per model call. Worst case is two calls (initial plus one retry) at 60s, which
can exceed the harness ceiling of 120s, so the retry is given the remaining budget rather than a fresh 60s: the
process targets a hard 110s wall-clock cap and exits 6 if the budget is exhausted before an answer.

**Rationale**: `per_call_timeout_seconds` is 120 and a harness timeout is recorded as ERROR, which makes the whole run
INCONCLUSIVE. A self-imposed cap converts that into a clean, attributable exit 6.

**Alternatives considered**: 60s plus a full second 60s (rejected — can trip the 120s ceiling and poison the run);
30s timeouts (rejected — a cold 7B model on CPU can legitimately take longer on the first call).

---

## R10. Preserving the user's words (FR-16)

**Decision**: the prompt instructs the model to copy the user's own nouns and verbs into `task` and `target` —
trimming filler like "remind me to", never substituting synonyms, never adding detail. Code enforces the ceiling
(200 characters) and the non-empty requirement, not the wording.

**Rationale**: FR-16 is a fidelity rule, and the Layer-3 check looks for the user's keywords (or a word starting with
them) inside `task`/`target`. A paraphrase such as "dental visit" for "call the dentist" is the realistic way to fail
it, so the instruction is to echo, not to rewrite.

**Alternatives considered**: extracting the span in code by stripping lead-in phrases (rejected — brittle across
phrasings, and it is exactly the language work the model is for); allowing the model to summarise (rejected — loses
keywords and risks adding facts, breaking FR-16 in both directions).

# Implementation Plan: Reminder Agent

**Branch**: `main` (feature directory `001-reminder-agent-cli`) | **Date**: 2026-11-14 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-reminder-agent-cli/spec.md`

**Note**: This template is filled in by the `/speckit-plan` command; its definition describes the execution workflow.

## Summary

One command turns one natural-language reminder request into one validated JSON decision on stdout. A deterministic
input guard refuses blank, over-long and credential-bearing input before any model call; a local model is then used
only for *language understanding* — intent, task, target and the parts of a time expression — while every date and
time is computed in Java from `--now`, and every field is validated against the output schema and INV-01..INV-08
before a single byte is printed. Model and service failures exit 6; output that stays invalid after one retry exits 7.
Nothing is fabricated on either path.

## Technical Context

**Language/Version**: Java 17 (toolchain target 17; newer JDKs acceptable as long as `--release 17` holds)

**Primary Dependencies**: Jackson `jackson-databind` and `jackson-datatype-jsr310` (runtime); JUnit 5 (test scope
only). HTTP via `java.net.http.HttpClient` from the JDK. No LLM, agent or orchestration framework (constitution II).

**Storage**: N/A — the tool is stateless. One invocation decides about one utterance and persists nothing.

**Testing**: JUnit 5 via `mvn test`, covering `InputGuard`, `TimeResolver`, `DecisionMapper` and `OutputValidator`
with no model call. The evaluation harness (`python .harness/run.py`) is the outer, model-dependent check and is not
modified.

**Target Platform**: JVM 17 command line on Windows and Linux. Executable fat jar at `target/reminder-agent.jar`
(`maven-shade-plugin`, `finalName` `reminder-agent`), main class `com.atria.reminder.Main`.

**Project Type**: Single-module Maven CLI application.

**Performance Goals**: One decision per invocation well inside the harness ceiling of 120s per call
(`per_call_timeout_seconds`). HTTP read timeout 60s, so one retry plus overhead still fits. Guard-only refusals
(`EMPTY_INPUT`, `INPUT_TOO_LONG`, secret detection) return without any network call, in milliseconds.

**Constraints**:
- Offline except for a local Ollama endpoint; no cloud calls, no API keys (constitution III).
- Endpoint and model from `OLLAMA_URL`, `REMINDER_MODEL`, `REMINDER_TEMPERATURE` with the documented defaults.
- **stdout carries exactly one JSON document and nothing else** — the harness runs `json.loads` on the whole of it.
  All diagnostics, warnings and JVM noise go to stderr.
- `--now` is supplied per invocation and varies between evaluation cases; it MUST never be replaced by the system
  clock.
- Dates and times resolved with `java.time` in the offset carried by `--now`.

**Scale/Scope**: One utterance per run, input capped at 500 characters, `task`/`target` capped at 200 characters by
the output schema. Roughly a dozen classes; no concurrency.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-Phase-0 | Post-Phase-1 |
|---|---|---|---|
| I. Spec Authority | Plan implements only what `spec.md` requires; no change to `evals/`, `.harness/`, protected `evidence/`; no dataset special-casing; INV/FR identifiers preserved | PASS | PASS |
| II. Technology Constraints | Java 17, Maven, single module, Jackson + JUnit 5 only, JDK HttpClient, shaded `reminder-agent.jar` | PASS | PASS |
| III. Local Model Only | Ollama via `OLLAMA_URL`; structured output (`format` = extraction schema); no keys or secrets in repo | PASS | PASS |
| IV. Trust Boundary | Model output parsed and validated in Java before printing; time resolved by `java.time`, never trusted from the model; guards in code, not prompt-only | PASS | PASS |
| V. Failure Separation | Exit 2 validation, 6 `MODEL_UNAVAILABLE`, 7 `MODEL_OUTPUT_INVALID` after one retry, 0 success; no fabrication | PASS | PASS |
| VI. Safety | Input never executed; canary line in system prompt and never emitted; injection and secret requests refused | PASS | PASS |

Two design choices exist specifically to hold these gates:

- The model is **never asked for a timestamp**. It returns time *parts* (see `contracts/extraction-schema.json`), and
  `TimeResolver` computes the instant. A hallucinated date therefore cannot reach the output (IV), and FR-01..FR-06
  are deterministic and unit-testable offline.
- The guard runs **before** the prompt is built, so `EMPTY_INPUT`, `INPUT_TOO_LONG` and credential-bearing input never
  leave the process (FR-11, FR-14, VI), and the refusal path costs no tokens.

No violations. Complexity Tracking is therefore empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-reminder-agent-cli/
├── plan.md                      # This file (/speckit-plan command output)
├── spec.md                      # Feature specification
├── research.md                  # Phase 0 output
├── data-model.md                # Phase 1 output
├── quickstart.md                # Phase 1 output
├── contracts/                   # Phase 1 output
│   ├── cli-contract.md          # Invocation, exit codes, stream discipline
│   ├── extraction-schema.json   # Model-facing structured-output schema
│   └── prompt-contract.md       # System prompt obligations, canary, delimiting
├── checklists/
│   └── requirements.md          # Spec quality checklist
└── tasks.md                     # Phase 2 output (/speckit-tasks — NOT created here)
```

### Source Code (repository root)

The repository root for the build is `Part-1B/`, because the harness resolves the jar as
`<repo root>/target/reminder-agent.jar` and runs `mvn -B clean package` there.

```text
pom.xml                                        # single module, shade plugin, finalName reminder-agent
src/main/java/com/atria/reminder/
├── Main.java                                  # entry point: wires the pipeline, maps failures to exit codes
├── cli/
│   ├── Args.java                              # --now / --input parsing, offset-aware; exit 2 on bad input
│   └── ExitCode.java                          # 0, 2, 6, 7 as named constants
├── guard/
│   ├── InputGuard.java                        # FR-11 blank / >500 chars; delegates credential checks
│   └── SecretDetector.java                    # FR-14 credential terms + 13-19 digit runs
├── prompt/
│   ├── PromptBuilder.java                     # system prompt + few-shot + canary; user text delimited as data
│   └── resources (src/main/resources/)
│       ├── system-prompt.txt                  # includes the canary line
│       └── extraction-schema.json             # same contract as contracts/extraction-schema.json
├── model/
│   ├── OllamaClient.java                      # /api/chat, stream=false, format=<schema>, 60s timeout
│   ├── ModelUnavailableException.java         # -> exit 6
│   ├── Extraction.java                        # intent + task + target + TimeExpression + snooze + safety flag
│   └── ExtractionParser.java                  # strict Jackson parse of the model envelope
├── time/
│   ├── TimeResolver.java                      # FR-01..FR-06 in java.time, against --now's offset
│   └── Resolution.java                        # resolved instant, or a typed failure (PAST / INVALID / VAGUE)
├── decision/
│   └── DecisionMapper.java                    # precedence order; builds the Decision per FR-05..FR-16
└── output/
    ├── Decision.java                          # the eight output fields
    ├── OutputValidator.java                   # schema shape + INV-01..INV-08; drives the single retry
    └── JsonPrinter.java                       # one compact JSON document to stdout, nothing else

src/test/java/com/atria/reminder/
├── guard/InputGuardTest.java                  # blank, 500/501 boundary, credential terms, flight/room numbers
├── guard/SecretDetectorTest.java              # digit-run boundaries 12/13/19/20, spelled-out secrets
├── time/TimeResolverTest.java                 # FR-01..FR-06, offsets, midnight rollover, 366-day horizon
├── decision/DecisionMapperTest.java           # precedence, clarify field population, reason-code rules
└── output/OutputValidatorTest.java            # INV-01..INV-08 including canary and clarify restrictions
```

**Structure Decision**: single Maven module rooted at `Part-1B/`, with packages split by pipeline stage
(`cli`, `guard`, `prompt`, `model`, `time`, `decision`, `output`) rather than by technical layer. The stage boundary
is what the constitution's trust boundary needs: everything from `model` inward is untrusted, and `time`, `decision`
and `output` are pure, model-free and unit-testable. `DecisionMapper` is a separate stage from `OutputValidator`
because one builds the answer and the other refuses to let a bad answer out; collapsing them would make the retry
path and the invariant checks share state.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

No constitution violations. No entries.

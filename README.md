# Assignment 1 — Spec-Driven Development & Harness Engineering (SEZG534)

Two small Java applications built with the GitHub Spec Kit workflow (constitution → specify → clarify → plan →
tasks → analyze → implement) using Claude Code as the coding agent. Each one is validated by an external harness
that is independent of the generated code.

| Part | Application | Behaviour | Harness |
|---|---|---|---|
| [Part-1A](Part-1A/) | Smart Todo CLI | Deterministic | Checkstyle lint, SpotBugs + FindSecBugs SAST, JUnit + JaCoCo coverage, black-box CLI contract tests (AC-01..AC-16) |
| [Part-1B](Part-1B/) | Natural-Language Reminder Agent (local LLM via Ollama) | Non-deterministic | 25-case eval dataset; L1 schema → L2 invariants/properties → L3 semantic; 3 trials per case; threshold gate |

## Prerequisites
- JDK 17+ and Maven 3.9+ (`java -version`, `mvn -v`)
- Python 3.9+ (the harnesses use only the standard library)
- Part 1B only: [Ollama](https://ollama.com) running locally with the model pulled: `ollama pull qwen2.5:7b`
- No API keys are needed or stored anywhere in this repository.

## Run the harnesses
```bash
cd Part-1A
python .harness/run.py doctor            # toolchain check + pre-download of pinned Maven plugins
python .harness/run.py run --label demo  # all gates; evidence in evidence/runs/, summary in evidence/harness-results.md

cd ../Part-1B
python .harness/run.py doctor            # Java, Maven, Ollama and model check
python .harness/run.py run --label demo  # official evaluation (25 cases x 3 trials); evidence/eval-results.json
```
Exit code 0 means PASS. On failure, a structured feedback report is written to `evidence/feedback/`, and that report
is what gets fed back to the coding agent.

## Where to look
| What | Part-1A | Part-1B |
|---|---|---|
| Constitution | `.specify/memory/constitution.md` | `.specify/memory/constitution.md` |
| Spec, clarifications, plan, tasks | `specs/<feature>/` | `specs/<feature>/` |
| Inputs given to each Spec Kit step | `docs/speckit-inputs.md` | `docs/speckit-inputs.md` |
| Agent guardrails | `CLAUDE.md`, `.claude/settings.json` | `CLAUDE.md`, `.claude/settings.json` |
| Harness | `.harness/` | `.harness/` (`evaluators/structural.py`, `properties.py`, `semantic.py`) |
| Eval dataset / schema / threshold | — | `evals/dataset.json`, `evals/output.schema.json`, `evals/config.json` |
| First generation (before repair) | `evidence/initial-generation/` | `evidence/initial-generation/` |
| Final generation | `evidence/final-generation/` | `evidence/final-generation/` |
| Run history (auto) | `evidence/harness-results.md` | `evidence/harness-results.md`, `evidence/eval-results.json` |
| Narrative log + human decision | `evidence/harness-log.md` | `evidence/harness-log.md` |

## Anti-tampering
Harness files, the eval dataset/config and the agent rules are hash-locked in `.harness/lock.json`. Every run
verifies the lock, so a run fails if a test, case or threshold was changed outside a documented spec revision
(`evidence/lock-history.md`). Claude Code is also denied write access to those paths through `.claude/settings.json`.
The harness also rejects test-skip and suppression annotations, and gate overrides in `pom.xml`.

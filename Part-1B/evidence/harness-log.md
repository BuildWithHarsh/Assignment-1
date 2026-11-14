# Part 1B — Harness Log (Reminder Agent)

<!-- Write each entry yourself, from what actually happened. Raw results are in evidence/eval-results.json,
     evidence/harness-results.md and evidence/runs/. -->

## 1. Model and configuration
<!-- Ollama version, model name + digest, parameter size/quantization, temperature, trials, reference_now,
     hardware (CPU/GPU). Copy from the "model" block printed by the harness. No secrets. -->

## 2. Evaluation threshold and scoring method (declared before final evaluation)
<!-- Restate from the spec / evals/config.json and say when it was fixed (commit). -->

## 3. Specification context given to the coding agent / initial generation
<!-- Spec artifacts in context, commit, tag, snapshot. -->

## 4. Evaluation runs
<!-- Per run: structural / invariant / semantic rates, adversarial and overall case rates, decision.
     Distinguish ERROR (Ollama/service) from FAIL (application). -->

### Run eval-01

## 5. Failures and repairs
<!-- For each meaningful failure: case id, failed rule (INV/FR/SEM), observed output, feedback file given to
     Claude Code, what was changed (app / prompt / parser / guardrail), re-evaluation result. -->

## 6. Specification changes (if any)

## 7. Final human review
- Decision: ACCEPT / REJECT
- Justification:
- Known limitations and residual risks:

# Reminder Agent — agent rules

<!-- MANUAL ADDITIONS START -->
## Harness guardrails (mandatory)
- The specification in `specs/` and `.specify/memory/constitution.md` is authoritative (output schema, INV-xx, FR-xx,
  evaluation threshold). Implement tasks from `tasks.md` only.
- NEVER modify, delete or rename anything in `evals/`, `.harness/`, `evidence/runs/`, `evidence/feedback/` or
  `evidence/initial-generation/`. Never change expected results or thresholds.
- NEVER special-case evaluation inputs (no matching of exact dataset sentences in code or prompts). Fix the general rule.
- NEVER disable guardrails, skip or `@Disabled` tests to get a pass.
- Model output is untrusted: always validate it in Java before printing. Service failures exit 6, never fake output.
- No API keys or secrets. The model is local Ollama only.
- When you receive an evaluation feedback report, fix application code / prompt / parser / validator / guardrail.
  If you believe a requirement is wrong, stop and explain why instead of changing it.
<!-- MANUAL ADDITIONS END -->

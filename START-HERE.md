# Start here (personal run order — delete this file before you submit if you like)

## 0. One-time setup (Windows)
    uv tool install specify-cli --from git+https://github.com/github/spec-kit.git
    ollama pull qwen2.5:7b
Create an empty public GitHub repo named Assignment-1 and clone it.

## 1. Spec Kit init FIRST, then copy the kit in
    cd Assignment-1
    specify init Part-1A --ai claude --script ps
    specify init Part-1B --ai claude --script ps
Then copy this kit's files over the repo (merge folders). Check `git status`: Spec Kit's `.claude/commands/`
must still be there, next to the kit's `.claude/settings.json`.

## 2. Lock the harness before any code exists
    cd Part-1A
    python .harness/run.py doctor
    python .harness/run.py lock --reason "initial harness before implementation"
    cd ..\Part-1B
    python .harness/run.py doctor
    python .harness/run.py lock --reason "initial harness + eval dataset before implementation"
    cd .. && git add -A && git commit -m "Harness, eval dataset and agent guardrails"

## 3. For each part: open Claude Code inside that part's folder
Follow `docs/speckit-inputs.md` step by step. Commit after every Spec Kit command.
  - Part 1A: after /speckit.implement → commit, `git tag initial-generation`, `python .harness/run.py snapshot initial`
  - Part 1B: same, but tag `part1b-initial-generation`
Then run the harness → paste evidence/feedback/<newest>.md into Claude Code → re-run → … → `snapshot final`.

## 4. Write these yourself (AI policy)
  - Part-1A/evidence/harness-log.md and Part-1B/evidence/harness-log.md
  - The five reflection answers in the .docx template
Keep notes on what actually failed while you work; that's your raw material for both.

## 5. Video (5–7 min), then submit
  - 1A: show spec → AC → contract test; run harness; show one feedback file and the repair commit
  - 1B: show schema/invariants/dataset; run eval; show eval-results metrics and a failure → repair
  - Repo public; video shared with BITS IDs; only the .docx goes to the LMS

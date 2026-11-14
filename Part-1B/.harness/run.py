#!/usr/bin/env python3
"""Part 1B non-deterministic evaluation harness (Reminder Agent).

Commands (run from the Part-1B folder):
    python .harness/run.py doctor                       check Java, Maven, Ollama and the configured model
    python .harness/run.py run --label eval-01          OFFICIAL evaluation (config trials, all cases)
    python .harness/run.py run --label dev --trials 1 [--cases N01,G03]   quick UNOFFICIAL check
    python .harness/run.py snapshot initial|final       copy src/ (+ pom.xml, prompts/) into evidence/
    python .harness/run.py lock --reason "..."          (human only) re-hash harness + evals after a spec change

Pipeline per trial: dataset case -> java -jar target/reminder-agent.jar -> output
  -> L1 schema (evals/output.schema.json) -> L2 invariants + case properties -> L3 semantic -> trial verdict
Cases aggregate over trials; metrics are compared with the thresholds declared in evals/config.json.
Standard library only (Python 3.9+).
"""
import argparse
import datetime as dt
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
sys.path.insert(0, HERE)
from evaluators.properties import case_properties, invariants  # noqa: E402
from evaluators.semantic import judge, semantic  # noqa: E402
from evaluators.structural import validate  # noqa: E402

EVID = os.path.join(ROOT, "evidence")
EVALS = os.path.join(ROOT, "evals")
LOCK = os.path.join(HERE, "lock.json")
JAR = os.path.join(ROOT, "target", "reminder-agent.jar")
EXTRA_LOCKED = ["evals", "CLAUDE.md", os.path.join(".claude", "settings.json")]
FORBIDDEN_IN_SRC = [r"@(?:[\w.]+\.)?Disabled\b", r"@(?:[\w.]+\.)?Ignore\b", r"\bAssumptions\.assume"]
FORBIDDEN_IN_POM = ["testFailureIgnore", "<skipTests>true", "maven.test.skip"]


def load(path):
    with open(path, encoding="utf-8") as f:
        return json.load(f)


def now_local():
    return dt.datetime.now().astimezone()


def mvn():
    return shutil.which("mvn") or shutil.which("mvn.cmd") or "mvn"


def git(*args):
    try:
        p = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, timeout=20)
        return p.stdout.strip() if p.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def strip_jvm_banner(text):
    return "\n".join(ln for ln in (text or "").splitlines() if not ln.startswith("Picked up "))


# ---- integrity --------------------------------------------------------------------------
def locked_files():
    files = []
    for base_rel in [".harness"] + EXTRA_LOCKED:
        base = os.path.join(ROOT, base_rel)
        if os.path.isfile(base):
            files.append(base_rel)
            continue
        for d, dirs, names in os.walk(base):
            dirs[:] = [x for x in dirs if x not in ("__pycache__", "tools")]
            for n in names:
                p = os.path.join(d, n)
                if os.path.abspath(p) != LOCK:
                    files.append(os.path.relpath(p, ROOT))
    return sorted(f.replace("\\", "/") for f in files)


def sha(path):
    with open(os.path.join(ROOT, path), "rb") as f:
        return hashlib.sha256(f.read().replace(b"\r\n", b"\n")).hexdigest()


def check_integrity():
    problems = []
    if not os.path.exists(LOCK):
        return ["No .harness/lock.json - a human must run: python .harness/run.py lock --reason \"initial\""]
    lock = load(LOCK)["files"]
    cur = {p: sha(p) for p in locked_files()}
    problems += ["Locked file missing: " + p for p in lock if p not in cur]
    problems += ["Locked file modified: " + p for p in lock if p in cur and cur[p] != lock[p]]
    problems += ["Unlocked file added: " + p for p in cur if p not in lock]
    pom = os.path.join(ROOT, "pom.xml")
    if os.path.exists(pom):
        txt = open(pom, encoding="utf-8").read()
        problems += ["pom.xml contains forbidden override '%s'" % w for w in FORBIDDEN_IN_POM if w in txt]
    for path in glob.glob(os.path.join(ROOT, "src", "**", "*.java"), recursive=True):
        txt = open(path, encoding="utf-8", errors="replace").read()
        for w in FORBIDDEN_IN_SRC:
            m = re.search(w, txt)
            if m:
                problems.append("%s contains forbidden test skip '%s'" % (os.path.relpath(path, ROOT), m.group(0)))
    return problems


# ---- model environment ------------------------------------------------------------------
def ollama_info(url, model, timeout=10):
    info = {"url": url, "model": model}
    try:
        with urllib.request.urlopen(url.rstrip("/") + "/api/version", timeout=timeout) as r:
            info["ollama_version"] = json.loads(r.read().decode()).get("version")
        req = urllib.request.Request(url.rstrip("/") + "/api/show", data=json.dumps({"model": model}).encode(),
                                     headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(req, timeout=timeout) as r:
            d = json.loads(r.read().decode())
            det = d.get("details", {})
            info.update({"model_family": det.get("family"), "parameter_size": det.get("parameter_size"),
                         "quantization": det.get("quantization_level")})
        with urllib.request.urlopen(url.rstrip("/") + "/api/tags", timeout=timeout) as r:
            for m in json.loads(r.read().decode()).get("models", []):
                if m.get("name") == model or m.get("model") == model:
                    info["model_digest"] = (m.get("digest") or "")[:12]
        info["reachable"] = True
    except (urllib.error.URLError, OSError, ValueError) as e:
        info["reachable"] = False
        info["error"] = str(e)
    return info


# ---- one trial --------------------------------------------------------------------------
def case_input(case):
    if "input_repeat" in case:
        return case["input_repeat"]["text"] * case["input_repeat"]["times"]
    return case["input"]


def run_trial(case, cfg, schema, env):
    now_s = case.get("now", cfg["reference_now"])
    now = dt.datetime.fromisoformat(now_s)
    text = case_input(case)
    cmd = ["java", "-jar", JAR, "--now", now_s, "--input", text]
    t0 = time.time()
    tr = {"exit": None, "stdout": "", "stderr": "", "verdict": None, "L1": [], "L2": [], "L3": []}
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=cfg["per_call_timeout_seconds"], env=env,
                           encoding="utf-8", errors="replace")
        tr.update(exit=p.returncode, stdout=p.stdout.strip(), stderr=strip_jvm_banner(p.stderr).strip()[:2000])
    except subprocess.TimeoutExpired:
        tr.update(exit="timeout", verdict="ERROR", error="call exceeded %ss" % cfg["per_call_timeout_seconds"])
    tr["seconds"] = round(time.time() - t0, 2)
    if tr["verdict"] == "ERROR":
        return tr
    if tr["exit"] == 6:
        tr.update(verdict="ERROR", error="MODEL_UNAVAILABLE (service failure, not an application failure)")
        return tr
    if tr["exit"] != 0:
        tr["L1"] = ["INV-01 exit code %s (expected 0); stderr: %s" % (tr["exit"], tr["stderr"][:300])]
        tr["verdict"] = "FAIL"
        return tr
    try:
        out = json.loads(tr["stdout"])
    except ValueError:
        tr["L1"] = ["INV-01 stdout is not a single JSON document"]
        tr["verdict"] = "FAIL"
        return tr
    tr["output"] = out
    tr["L1"] = validate(out, schema)
    if not tr["L1"]:
        tr["L2"] = invariants(out, now, cfg.get("canary")) + case_properties(out, case["expect"])
        if not tr["L2"]:
            tr["L3"] = semantic(out, case["expect"], cfg["due_tolerance_minutes"])
            if cfg.get("judge", {}).get("enabled"):
                j = judge(cfg["model"]["ollama_url"], cfg["judge"]["model"], text, out)
                if j is not None:
                    tr["judge"] = j
                    if "score" in j and j["score"] < 1:
                        tr["L3"].append("SEM-JUDGE rubric score %d: %s" % (j["score"], j.get("reason", "")))
    tr["verdict"] = "PASS" if not (tr["L1"] or tr["L2"] or tr["L3"]) else "FAIL"
    return tr


# ---- aggregate --------------------------------------------------------------------------
def aggregate(cases_out, cfg, official):
    th = cfg["thresholds"]
    trials = [t for c in cases_out for t in c["trials"]]
    scored = [t for t in trials if t["verdict"] != "ERROR"]
    l1 = [t for t in scored if not t["L1"]]
    l2 = [t for t in l1 if not t["L2"]]
    l3 = [t for t in l2 if not t["L3"]]
    rate = lambda a, b: round(len(a) / len(b), 4) if b else None
    by_cat = {}
    for c in cases_out:
        b = by_cat.setdefault(c["category"], {"cases": 0, "passed": 0, "error": 0})
        b["cases"] += 1
        b["passed"] += c["verdict"] == "PASS"
        b["error"] += c["verdict"] == "ERROR"
    nonerr = [c for c in cases_out if c["verdict"] != "ERROR"]
    adv = [c for c in nonerr if c["category"] == "adversarial"]
    m = {
        "cases": len(cases_out), "trials": len(trials), "error_trials": len(trials) - len(scored),
        "error_cases": sum(c["verdict"] == "ERROR" for c in cases_out),
        "structural_trial_rate": rate(l1, scored),
        "invariant_trial_rate_given_L1": rate(l2, l1),
        "semantic_trial_rate_given_L2": rate(l3, l2),
        "trial_pass_rate": rate(l3, scored),
        "overall_case_rate": rate([c for c in nonerr if c["verdict"] == "PASS"], nonerr),
        "adversarial_case_rate": rate([c for c in adv if c["verdict"] == "PASS"], adv),
        "consistent_cases": sum(len({t["verdict"] for t in c["trials"]}) == 1 for c in cases_out),
        "by_category": by_cat,
    }
    checks = {
        "structural_trial_rate >= %s" % th["structural_trial_rate"]:
            m["structural_trial_rate"] is not None and m["structural_trial_rate"] >= th["structural_trial_rate"],
        "adversarial_case_rate >= %s" % th["adversarial_case_rate"]:
            m["adversarial_case_rate"] is not None and m["adversarial_case_rate"] >= th["adversarial_case_rate"],
        "overall_case_rate >= %s" % th["overall_case_rate"]:
            m["overall_case_rate"] is not None and m["overall_case_rate"] >= th["overall_case_rate"],
    }
    if m["error_cases"] > th["max_error_cases"]:
        decision = "INCONCLUSIVE"
    else:
        decision = "PASS" if all(checks.values()) else "FAIL"
    if not official:
        decision += " (unofficial)"
    return m, checks, decision


# ---- evidence ---------------------------------------------------------------------------
def write_feedback(path, label, commit, cases_out, metrics, checks, decision):
    L = ["# Evaluation feedback - %s - %s" % (label, decision), "", "Commit under test: `%s`" % commit, "",
         "You are the coding agent. The external evaluation harness found the failures below. Spec context: `specs/` "
         "(output schema, INV-xx invariants, FR-xx rules, evaluation threshold) and `.specify/memory/constitution.md`.",
         "", "**Rules:** fix application code, prompt construction, parsing, validation or guardrails. Do NOT edit "
         "`evals/`, `.harness/`, expected results or thresholds, and do not special-case eval inputs (no hard-coded "
         "matching of these exact sentences). Fix the general behaviour the failed rule describes.", "",
         "## Threshold checks", ""]
    L += ["- [%s] %s" % ("x" if ok else " ", k) for k, ok in checks.items()]
    L += ["", "Metrics: `%s`" % json.dumps({k: v for k, v in metrics.items() if k != "by_category"}), ""]
    for c in cases_out:
        if c["verdict"] == "PASS":
            continue
        L += ["## %s [%s] - %s (%d/%d trials passed)" % (c["id"], c["category"], c["verdict"], c["passed_trials"],
                                                         len(c["trials"])),
              "- Requirements: %s" % ", ".join(c["refs"]),
              "- Input: `%s`" % json.dumps(c["input"])[:400],
              "- Expected: `%s`" % json.dumps(c["expect"]),
              "- Rubric: %s" % c.get("rubric", "")]
        shown = 0
        for i, t in enumerate(c["trials"], 1):
            if t["verdict"] == "PASS" or shown >= 2:
                continue
            shown += 1
            L.append("- Trial %d (%s, exit %s): observed `%s`" % (
                i, t["verdict"], t["exit"], (t["stdout"] or t.get("error") or t["stderr"])[:600]))
            for f in t["L1"] + t["L2"] + t["L3"]:
                L.append("  - failed: %s" % f)
        L.append("")
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")


def append_results(ts, label, commit, model, trials, metrics, decision):
    path = os.path.join(EVID, "harness-results.md")
    new = not os.path.exists(path)
    pct = lambda v: "n/a" if v is None else "%.0f%%" % (v * 100)
    with open(path, "a", encoding="utf-8") as f:
        if new:
            f.write("# Evaluation results (auto-generated by .harness/run.py - do not edit by hand)\n\n"
                    "| Time | Label | Commit | Model | Trials | Structural | Invariant | Semantic | Adversarial cases "
                    "| Overall cases | Error cases | Decision |\n|---|---|---|---|---|---|---|---|---|---|---|---|\n")
        f.write("| %s | %s | %s | %s | %d | %s | %s | %s | %s | %s | %d | **%s** |\n" % (
            ts, label, commit, model, trials, pct(metrics["structural_trial_rate"]),
            pct(metrics["invariant_trial_rate_given_L1"]), pct(metrics["semantic_trial_rate_given_L2"]),
            pct(metrics["adversarial_case_rate"]), pct(metrics["overall_case_rate"]), metrics["error_cases"],
            decision))


# ---- commands ---------------------------------------------------------------------------
def build(rd):
    try:
        p = subprocess.run([mvn(), "-B", "clean", "package"], cwd=ROOT, capture_output=True, text=True, timeout=900)
        out, code = p.stdout + p.stderr, p.returncode
    except (OSError, subprocess.SubprocessError) as e:
        out, code = str(e), -1
    with open(os.path.join(rd, "build.log"), "w", encoding="utf-8") as f:
        f.write(out)
    ok = code == 0 and os.path.exists(JAR)
    tail = [ln for ln in out.splitlines() if "[ERROR]" in ln or "Tests run:" in ln][:40]
    if code == 0 and not ok:
        tail.append("Build succeeded but target/reminder-agent.jar is missing (constitution II)")
    return ok, tail


def cmd_run(label, trials_override, only_cases, skip_build):
    cfg = load(os.path.join(EVALS, "config.json"))
    schema = load(os.path.join(EVALS, "output.schema.json"))
    data = load(os.path.join(EVALS, "dataset.json"))["cases"]
    official = trials_override is None and not only_cases and not skip_build
    trials = trials_override or cfg["trials"]
    if only_cases:
        data = [c for c in data if c["id"] in only_cases]
    t = now_local()
    ts = t.strftime("%Y%m%d-%H%M%S")
    rd = os.path.join(EVID, "runs", "%s_%s" % (ts, label))
    os.makedirs(rd, exist_ok=True)
    commit = (git("rev-parse", "--short", "HEAD") or "no-git") + (
        "+dirty" if git("status", "--porcelain", "--", ".", ":(exclude)evidence") else "")
    print("== Part 1B evaluation: %s @ %s (commit %s)%s" % (label, t.isoformat(timespec="seconds"), commit,
                                                            "" if official else "  [UNOFFICIAL]"))
    problems = check_integrity()
    if problems:
        print("INTEGRITY FAIL:\n  " + "\n  ".join(problems))
        if official:
            json.dump({"label": label, "decision": "FAIL", "integrity": problems},
                      open(os.path.join(rd, "eval-results.json"), "w"), indent=2)
            append_results(ts, label, commit, cfg["model"]["name"], trials,
                           {k: None for k in ("structural_trial_rate", "invariant_trial_rate_given_L1",
                                              "semantic_trial_rate_given_L2", "adversarial_case_rate",
                                              "overall_case_rate")} | {"error_cases": 0}, "FAIL (integrity)")
            sys.exit(1)
    if not skip_build:
        print("-- build (mvn clean package, includes unit tests) ...", end=" ", flush=True)
        ok, tail = build(rd)
        print("PASS" if ok else "FAIL")
        if not ok:
            print("\n".join("     " + x for x in tail))
            sys.exit(1)
    model = ollama_info(cfg["model"]["ollama_url"], cfg["model"]["name"])
    print("-- model: %s" % json.dumps(model))
    env = dict(os.environ, OLLAMA_URL=cfg["model"]["ollama_url"], REMINDER_MODEL=cfg["model"]["name"],
               REMINDER_TEMPERATURE=str(cfg["model"]["temperature"]))
    cases_out = []
    for c in data:
        res = [run_trial(c, cfg, schema, env) for _ in range(trials)]
        passed = sum(r["verdict"] == "PASS" for r in res)
        need = min(cfg["case_pass_min_trials"], trials)
        verdict = "ERROR" if any(r["verdict"] == "ERROR" for r in res) else ("PASS" if passed >= need else "FAIL")
        cases_out.append({"id": c["id"], "category": c["category"], "refs": c["refs"], "input": case_input(c),
                          "expect": c["expect"], "rubric": c.get("rubric"), "verdict": verdict,
                          "passed_trials": passed, "trials": res})
        marks = "".join({"PASS": ".", "FAIL": "F", "ERROR": "E"}[r["verdict"]] for r in res)
        print("   %-4s %-12s %-5s [%s]" % (c["id"], c["category"], verdict, marks))
        for r in res:
            for f in (r["L1"] + r["L2"] + r["L3"])[:3] + ([r["error"]] if r.get("error") else []):
                print("          - " + f[:200])
    metrics, checks, decision = aggregate(cases_out, cfg, official)
    result = {"label": label, "timestamp": t.isoformat(timespec="seconds"), "commit": commit, "official": official,
              "model": model, "temperature": cfg["model"]["temperature"], "trials_per_case": trials,
              "reference_now": cfg["reference_now"], "thresholds": cfg["thresholds"],
              "scoring": "trial PASS = L1 and L2 and L3 pass; case PASS = >= %d trials pass; any ERROR trial -> case "
                         "ERROR; decision per thresholds" % cfg["case_pass_min_trials"],
              "threshold_checks": checks, "metrics": metrics, "decision": decision, "cases": cases_out}
    with open(os.path.join(rd, "eval-results.json"), "w", encoding="utf-8") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
    shutil.copy(os.path.join(rd, "eval-results.json"), os.path.join(EVID, "eval-results.json"))
    append_results(ts, label, commit, cfg["model"]["name"], trials, metrics, decision)
    print("== metrics: " + json.dumps({k: v for k, v in metrics.items() if k != "by_category"}))
    for k, ok in checks.items():
        print("   [%s] %s" % ("x" if ok else " ", k))
    print("== DECISION: %s   evidence: %s" % (decision, os.path.relpath(rd, ROOT)))
    if not decision.startswith("PASS"):
        os.makedirs(os.path.join(EVID, "feedback"), exist_ok=True)
        fb = os.path.join(EVID, "feedback", "%s_%s.md" % (ts, label))
        write_feedback(fb, label, commit, cases_out, metrics, checks, decision)
        print("== Feedback for the coding agent: %s" % os.path.relpath(fb, ROOT))
        if decision.startswith("INCONCLUSIVE"):
            print("   (service errors occurred - check that Ollama is running, then re-run; this is not an app failure)")
    sys.exit(0 if decision.startswith("PASS") else 1)


def cmd_doctor():
    cfg = load(os.path.join(EVALS, "config.json"))
    ok = True
    for name, cmd in (("java", ["java", "-version"]), ("maven", [mvn(), "-v"])):
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            first = [x for x in (p.stdout + p.stderr).splitlines() if x and not x.startswith("Picked up")][:1]
            print("%-7s %s %s" % (name, "OK " if p.returncode == 0 else "BAD", first[0] if first else ""))
            ok &= p.returncode == 0
        except OSError as e:
            print("%-7s MISSING (%s)" % (name, e))
            ok = False
    info = ollama_info(cfg["model"]["ollama_url"], cfg["model"]["name"])
    if not info["reachable"]:
        print("ollama  BAD  not reachable at %s (%s) - start it with: ollama serve" % (info["url"], info.get("error")))
        ok = False
    elif "model_digest" not in info:
        print("ollama  BAD  model %s not pulled - run: ollama pull %s" % (info["model"], info["model"]))
        ok = False
    else:
        print("ollama  OK  v%s, %s %s %s digest %s" % (info.get("ollama_version"), info["model"],
                                                    info.get("parameter_size"), info.get("quantization"),
                                                    info["model_digest"]))
    print("python  OK  %s" % sys.version.split()[0])
    print("doctor:", "all good" if ok else "fix the problems above first")
    sys.exit(0 if ok else 1)


def cmd_snapshot(which, force):
    dst = os.path.join(EVID, "%s-generation" % which)
    if os.path.exists(dst) and not force:
        sys.exit("%s already exists; refusing to overwrite (use --force only if you are sure)" % dst)
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(dst)
    for item in ("src", "prompts", "pom.xml"):
        s = os.path.join(ROOT, item)
        if os.path.isdir(s):
            shutil.copytree(s, os.path.join(dst, item))
        elif os.path.exists(s):
            shutil.copy(s, dst)
    with open(os.path.join(dst, "SNAPSHOT.md"), "w", encoding="utf-8") as f:
        f.write("Snapshot: %s generation\nTaken: %s\nCommit: %s\n" % (
            which, now_local().isoformat(timespec="seconds"), git("rev-parse", "HEAD") or "no-git"))
    print("Snapshot written to", os.path.relpath(dst, ROOT))


def cmd_lock(reason):
    old = load(LOCK)["files"] if os.path.exists(LOCK) else {}
    new = {p: sha(p) for p in locked_files()}
    with open(LOCK, "w", encoding="utf-8") as f:
        json.dump({"locked_at": now_local().isoformat(timespec="seconds"), "reason": reason, "files": new}, f, indent=2)
    changed = sorted(set(old) ^ set(new) | {p for p in new if p in old and old[p] != new[p]})
    os.makedirs(EVID, exist_ok=True)
    with open(os.path.join(EVID, "lock-history.md"), "a", encoding="utf-8") as f:
        f.write("- %s | commit %s | reason: %s | changed: %s\n" % (
            now_local().isoformat(timespec="seconds"), git("rev-parse", "--short", "HEAD") or "no-git", reason,
            ", ".join(changed) or "none"))
    print("Locked %d files. Changed: %s" % (len(new), ", ".join(changed) or "none"))


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("doctor")
    r = sub.add_parser("run")
    r.add_argument("--label", default="eval")
    r.add_argument("--trials", type=int, help="override trial count (makes the run unofficial)")
    r.add_argument("--cases", help="comma-separated case ids (makes the run unofficial)")
    r.add_argument("--skip-build", action="store_true", help="reuse existing jar (makes the run unofficial)")
    s = sub.add_parser("snapshot")
    s.add_argument("which", choices=["initial", "final"])
    s.add_argument("--force", action="store_true")
    lk = sub.add_parser("lock")
    lk.add_argument("--reason", required=True)
    a = ap.parse_args()
    if a.cmd == "doctor":
        cmd_doctor()
    elif a.cmd == "run":
        cmd_run(a.label, a.trials, set(a.cases.split(",")) if a.cases else None, a.skip_build)
    elif a.cmd == "snapshot":
        cmd_snapshot(a.which, a.force)
    else:
        cmd_lock(a.reason)


if __name__ == "__main__":
    main()

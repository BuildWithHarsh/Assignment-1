#!/usr/bin/env python3
"""Part 1A deterministic harness runner (Smart Todo CLI).

Commands (run from the Part-1A folder):
    python .harness/run.py doctor                   check Java/Maven and pre-download pinned plugins
    python .harness/run.py run --label run-01       run all gates, record evidence, write feedback on failure
    python .harness/run.py snapshot initial|final   copy src/ + pom.xml into evidence/<x>-generation/
    python .harness/run.py lock --reason "..."      (human only) re-hash harness files after a legitimate change

Gates: integrity -> build -> lint (Checkstyle) -> sast (SpotBugs+FindSecBugs) -> unit (JUnit+JaCoCo) -> contract
All tool configuration lives in .harness/ and is passed on the command line, so pom.xml cannot weaken it.
Standard library only; works on Windows, macOS and Linux (needs Python 3.9+, JDK 17+, Maven 3.9+).
"""
import argparse
import csv
import datetime as dt
import glob
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
EVID = os.path.join(ROOT, "evidence")
LOCK = os.path.join(HERE, "lock.json")
JAR = os.path.join(ROOT, "target", "todo-cli.jar")

# ---- pinned tool versions & thresholds (constitution III) -------------------------------
CHECKSTYLE_PLUGIN = "org.apache.maven.plugins:maven-checkstyle-plugin:3.6.0"
SPOTBUGS_PLUGIN = "com.github.spotbugs:spotbugs-maven-plugin:4.8.6.6"
FINDSECBUGS = "com.h3xstream.findsecbugs:findsecbugs-plugin:1.13.0"
JACOCO = "org.jacoco:jacoco-maven-plugin:0.8.12"
MIN_LINE_COVERAGE = 0.80
FORBIDDEN_IN_POM = ["checkstyle", "spotbugs", "jacoco", "testFailureIgnore", "<skipTests>true", "<skip>true",
                    "maven.test.skip"]
FORBIDDEN_IN_SRC = [r"@(?:[\w.]+\.)?SuppressFBWarnings\b", r"CHECKSTYLE[:.]OFF", r"@(?:[\w.]+\.)?Disabled\b",
                    r"@(?:[\w.]+\.)?SuppressWarnings\s*\(", r"\bAssumptions\.assume", r"@(?:[\w.]+\.)?Ignore\b"]
# files whose hashes are locked (relative to ROOT); .harness/** is added automatically
EXTRA_LOCKED = ["CLAUDE.md", os.path.join(".claude", "settings.json")]


def mvn():
    return shutil.which("mvn") or shutil.which("mvn.cmd") or "mvn"


def now():
    return dt.datetime.now().astimezone()


def git(*args):
    try:
        p = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True, timeout=20)
        return p.stdout.strip() if p.returncode == 0 else None
    except (OSError, subprocess.SubprocessError):
        return None


def sh(cmd, log_path, timeout=900):
    """Run a command, tee combined output to log_path, return (exit_code, text)."""
    try:
        p = subprocess.run(cmd, cwd=ROOT, capture_output=True, text=True, timeout=timeout)
        out = (p.stdout or "") + ("\n--- stderr ---\n" + p.stderr if p.stderr else "")
        code = p.returncode
    except subprocess.TimeoutExpired:
        out, code = "TIMEOUT after %ss" % timeout, -1
    except OSError as e:
        out, code = "Cannot start %s: %s" % (cmd[0], e), -1
    with open(log_path, "w", encoding="utf-8") as f:
        f.write("$ " + " ".join(cmd) + "\n\n" + out)
    return code, out


def error_lines(text, limit=60):
    keep = [ln for ln in text.splitlines() if "[ERROR]" in ln or "[WARN" in ln or "BUILD FAILURE" in ln]
    keep = [ln for ln in keep if "Help 1" not in ln and "-e switch" not in ln and "full debug" not in ln
            and "Re-run Maven" not in ln and "For more information" not in ln and ln.strip() != "[ERROR]"]
    return keep[:limit] or text.splitlines()[-limit:]


# ---- integrity --------------------------------------------------------------------------
def locked_files():
    files = []
    for base, dirs, names in os.walk(HERE):
        dirs[:] = [d for d in dirs if d not in ("tools", "__pycache__")]
        for n in names:
            p = os.path.join(base, n)
            if os.path.abspath(p) != LOCK:
                files.append(os.path.relpath(p, ROOT))
    files += [f for f in EXTRA_LOCKED if os.path.exists(os.path.join(ROOT, f))]
    return sorted(f.replace("\\", "/") for f in files)


def sha(path):
    with open(os.path.join(ROOT, path), "rb") as f:
        return hashlib.sha256(f.read().replace(b"\r\n", b"\n")).hexdigest()


def gate_integrity(rd):
    problems = []
    if not os.path.exists(LOCK):
        problems.append("No .harness/lock.json - a human must run: python .harness/run.py lock --reason \"initial\"")
    else:
        with open(LOCK, encoding="utf-8") as f:
            lock = json.load(f)["files"]
        current = {p: sha(p) for p in locked_files()}
        for p, h in lock.items():
            if p not in current:
                problems.append("Locked file missing: " + p)
            elif current[p] != h:
                problems.append("Locked file modified: " + p)
        for p in current:
            if p not in lock:
                problems.append("Unlocked file added to harness: " + p)
    pom = os.path.join(ROOT, "pom.xml")
    if os.path.exists(pom):
        txt = open(pom, encoding="utf-8").read()
        problems += ["pom.xml contains forbidden gate override: '%s'" % w for w in FORBIDDEN_IN_POM if w in txt]
    for path in glob.glob(os.path.join(ROOT, "src", "**", "*.java"), recursive=True):
        txt = open(path, encoding="utf-8", errors="replace").read()
        for w in FORBIDDEN_IN_SRC:
            m = re.search(w, txt)
            if m:
                problems.append("%s contains forbidden suppression/skip '%s'" % (os.path.relpath(path, ROOT), m.group(0)))
    with open(os.path.join(rd, "integrity.log"), "w", encoding="utf-8") as f:
        f.write("\n".join(problems) or "OK")
    return {"status": "PASS" if not problems else "FAIL", "details": problems}


# ---- gates ------------------------------------------------------------------------------
def gate_build(rd):
    code, out = sh([mvn(), "-B", "-DskipTests", "clean", "package"], os.path.join(rd, "build.log"))
    ok = code == 0 and os.path.exists(JAR)
    det = error_lines(out) if code != 0 else ([] if ok else ["Build succeeded but target/todo-cli.jar missing "
                                                              "(constitution II requires shade finalName todo-cli)"])
    return {"status": "PASS" if ok else "FAIL", "details": det, "command": "mvn -B -DskipTests clean package"}


def gate_lint(rd):
    cmd = [mvn(), "-B", CHECKSTYLE_PLUGIN + ":check",
           "-Dcheckstyle.config.location=" + os.path.join(HERE, "lint", "checkstyle.xml"),
           "-Dcheckstyle.consoleOutput=true", "-Dcheckstyle.failOnViolation=true",
           "-Dcheckstyle.violationSeverity=warning", "-Dcheckstyle.includeTestSourceDirectory=false",
           "-Dcheckstyle.maxAllowedViolations=0"]
    code, out = sh(cmd, os.path.join(rd, "lint.log"))
    viol = [ln for ln in out.splitlines() if ln.startswith("[ERROR] ") and ".java" in ln]
    return {"status": "PASS" if code == 0 else "FAIL", "details": viol or (error_lines(out) if code else []),
            "violations": len(viol), "command": "checkstyle:check with .harness/lint/checkstyle.xml"}


def findsecbugs_jar(rd):
    tools = os.path.join(HERE, "tools")
    hits = glob.glob(os.path.join(tools, "findsecbugs-plugin-*.jar"))
    if hits:
        return hits[0]
    os.makedirs(tools, exist_ok=True)
    sh([mvn(), "-B", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:copy",
        "-Dartifact=" + FINDSECBUGS, "-DoutputDirectory=" + tools], os.path.join(rd, "sast-setup.log"))
    hits = glob.glob(os.path.join(tools, "findsecbugs-plugin-*.jar"))
    return hits[0] if hits else None


def gate_sast(rd):
    plugin = findsecbugs_jar(rd)
    if not plugin:
        return {"status": "FAIL", "details": ["Could not download FindSecBugs plugin (see sast-setup.log)"]}
    cmd = [mvn(), "-B", SPOTBUGS_PLUGIN + ":check", "-Dspotbugs.pluginList=" + plugin,
           "-Dspotbugs.effort=Max", "-Dspotbugs.threshold=Low", "-Dspotbugs.failOnError=true",
           "-Dspotbugs.includeTests=false", "-Dspotbugs.xmlOutput=true"]
    code, out = sh(cmd, os.path.join(rd, "sast.log"))
    bugs = [ln for ln in out.splitlines() if ln.startswith("[ERROR] ") and ("[" in ln[8:]) and
            ("High:" in ln or "Medium:" in ln or "Low:" in ln)]
    xml = os.path.join(ROOT, "target", "spotbugsXml.xml")
    if os.path.exists(xml):
        shutil.copy(xml, os.path.join(rd, "spotbugsXml.xml"))
    return {"status": "PASS" if code == 0 else "FAIL", "details": bugs or (error_lines(out) if code else []),
            "findings": len(bugs), "command": "spotbugs:check + FindSecBugs, effort=Max threshold=Low"}


def gate_unit(rd):
    cmd = [mvn(), "-B", JACOCO + ":prepare-agent", "test", JACOCO + ":report"]
    code, out = sh(cmd, os.path.join(rd, "unit.log"))
    tests = fails = skipped = 0
    failed = []
    for path in glob.glob(os.path.join(ROOT, "target", "surefire-reports", "TEST-*.xml")):
        root = ET.parse(path).getroot()
        tests += int(root.get("tests", 0))
        skipped += int(root.get("skipped", 0))
        for tc in root.iter("testcase"):
            for kind in ("failure", "error"):
                el = tc.find(kind)
                if el is not None:
                    fails += 1
                    failed.append("%s.%s: %s" % (tc.get("classname"), tc.get("name"),
                                                 (el.get("message") or (el.text or "")[:300]).strip()))
    cov = None
    csv_path = os.path.join(ROOT, "target", "site", "jacoco", "jacoco.csv")
    if os.path.exists(csv_path):
        missed = covered = 0
        with open(csv_path, encoding="utf-8") as f:
            for row in csv.DictReader(f):
                missed += int(row["LINE_MISSED"])
                covered += int(row["LINE_COVERED"])
        cov = covered / (missed + covered) if missed + covered else 0.0
        shutil.copy(csv_path, os.path.join(rd, "jacoco.csv"))
    det = list(failed)
    if code != 0 and not failed:
        det += error_lines(out)
    if tests == 0:
        det.append("No unit tests were executed")
    if skipped:
        det.append("%d tests skipped - skipping tests is not allowed" % skipped)
    if cov is None:
        det.append("No JaCoCo report produced")
    elif cov < MIN_LINE_COVERAGE:
        det.append("Line coverage %.1f%% is below required %.0f%%" % (cov * 100, MIN_LINE_COVERAGE * 100))
    ok = code == 0 and tests > 0 and not skipped and cov is not None and cov >= MIN_LINE_COVERAGE
    return {"status": "PASS" if ok else "FAIL", "tests": tests, "failed": fails, "skipped": skipped,
            "line_coverage": None if cov is None else round(cov, 4), "details": det,
            "command": "jacoco:prepare-agent test jacoco:report (min line coverage %.0f%%)" % (MIN_LINE_COVERAGE * 100)}


def gate_contract(rd):
    out_json = os.path.join(rd, "contract-results.json")
    code, out = sh([sys.executable, os.path.join(HERE, "contract_tests.py"), "--jar", JAR, "--out", out_json],
                   os.path.join(rd, "contract.log"))
    if not os.path.exists(out_json):
        return {"status": "FAIL", "details": error_lines(out)}
    res = json.load(open(out_json, encoding="utf-8"))
    failed = [c for c in res["cases"] if not c["passed"]]
    return {"status": "PASS" if code == 0 else "FAIL", "passed": res["passed"], "total": res["total"],
            "failed_cases": failed, "details": ["%s %s" % (c["ac"], c["name"]) for c in failed],
            "command": "python .harness/contract_tests.py (black-box, AC-01..AC-16 + CONST-IV)"}


# ---- evidence ---------------------------------------------------------------------------
def write_feedback(ts, label, gates, commit):
    os.makedirs(os.path.join(EVID, "feedback"), exist_ok=True)
    path = os.path.join(EVID, "feedback", "%s_%s.md" % (ts, label))
    L = ["# Harness feedback - %s (%s) - FAIL" % (label, ts), "",
         "Commit under test: `%s`" % commit, "",
         "You are the coding agent. The external harness rejected the implementation. Spec context: the feature spec "
         "and acceptance criteria in `specs/`, the constitution in `.specify/memory/constitution.md`, and `tasks.md`.",
         "", "**Rules:** fix the implementation only. Do not edit `.harness/`, tests derived from the spec, or the spec "
         "itself; do not add suppressions; do not lower thresholds. If you believe a requirement is wrong, stop and "
         "explain why instead of changing it.", ""]
    for name, g in gates.items():
        if g["status"] != "FAIL":
            continue
        L += ["## Gate failed: %s" % name, ""]
        if g.get("command"):
            L += ["Command: `%s`" % g["command"], ""]
        if name == "contract":
            for c in g.get("failed_cases", []):
                L.append("### %s - %s" % (c["ac"], c["name"]))
                for fl in c["failures"]:
                    L += ["- **Assertion:** %s" % fl["assertion"],
                          "  - Expected: `%s`" % json.dumps(fl["expected"])[:500],
                          "  - Observed: `%s`" % json.dumps(fl["observed"])[:900]]
                L.append("")
        else:
            L += ["```"] + [str(d) for d in g.get("details", [])[:60]] + ["```", ""]
    L += ["Full logs: `evidence/runs/%s_%s/`" % (ts, label)]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(L) + "\n")
    return path


def append_results_table(ts, label, commit, gates, overall):
    path = os.path.join(EVID, "harness-results.md")
    new = not os.path.exists(path)
    with open(path, "a", encoding="utf-8") as f:
        if new:
            f.write("# Harness results (auto-generated by .harness/run.py - do not edit by hand)\n\n"
                    "| Time | Label | Commit | Integrity | Build | Lint | SAST | Unit (cov) | Contract | Overall |\n"
                    "|---|---|---|---|---|---|---|---|---|---|\n")
        u = gates.get("unit", {})
        cov = "" if u.get("line_coverage") is None else " (%.0f%%)" % (u["line_coverage"] * 100)
        c = gates.get("contract", {})
        cc = " %s/%s" % (c.get("passed"), c.get("total")) if c.get("total") else ""
        g = lambda k: gates.get(k, {}).get("status", "SKIP")
        f.write("| %s | %s | %s | %s | %s | %s | %s | %s%s | %s%s | **%s** |\n" % (
            ts, label, commit, g("integrity"), g("build"), g("lint"), g("sast"), g("unit"), cov,
            g("contract"), cc, overall))


def cmd_run(label):
    t = now()
    ts = t.strftime("%Y%m%d-%H%M%S")
    rd = os.path.join(EVID, "runs", "%s_%s" % (ts, label))
    os.makedirs(rd, exist_ok=True)
    commit = (git("rev-parse", "--short", "HEAD") or "no-git") + ("+dirty" if git("status", "--porcelain", "--", ".", ":(exclude)evidence") else "")
    gates = {}
    print("== Part 1A harness: %s @ %s (commit %s)" % (label, t.isoformat(timespec="seconds"), commit))
    order = [("integrity", gate_integrity), ("build", gate_build), ("lint", gate_lint), ("sast", gate_sast),
             ("unit", gate_unit), ("contract", gate_contract)]
    for name, fn in order:
        if name in ("sast", "contract") and gates.get("build", {}).get("status") != "PASS":
            gates[name] = {"status": "SKIP", "details": ["skipped because build failed"]}
        else:
            print("-- %s ..." % name, end=" ", flush=True)
            gates[name] = fn(rd)
        print(gates[name]["status"] if name in gates else "")
        for d in gates[name].get("details", [])[:8]:
            print("     " + str(d)[:220])
    overall = "PASS" if all(g["status"] == "PASS" for g in gates.values()) else "FAIL"
    summary = {"label": label, "timestamp": t.isoformat(timespec="seconds"), "commit": commit,
               "overall": overall, "gates": gates,
               "env": {"python": sys.version.split()[0], "platform": sys.platform}}
    with open(os.path.join(rd, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2)
    append_results_table(ts, label, commit, gates, overall)
    print("== OVERALL: %s   evidence: %s" % (overall, os.path.relpath(rd, ROOT)))
    if overall == "FAIL":
        print("== Feedback for the coding agent: %s" % os.path.relpath(write_feedback(ts, label, gates, commit), ROOT))
    sys.exit(0 if overall == "PASS" else 1)


def cmd_snapshot(which, force):
    dst = os.path.join(EVID, "%s-generation" % which)
    if os.path.exists(dst) and not force:
        sys.exit("%s already exists; refusing to overwrite (use --force only if you are sure)" % dst)
    shutil.rmtree(dst, ignore_errors=True)
    os.makedirs(dst)
    for item in ("src", "pom.xml"):
        s = os.path.join(ROOT, item)
        if os.path.isdir(s):
            shutil.copytree(s, os.path.join(dst, item))
        elif os.path.exists(s):
            shutil.copy(s, dst)
    with open(os.path.join(dst, "SNAPSHOT.md"), "w", encoding="utf-8") as f:
        f.write("Snapshot: %s generation\nTaken: %s\nCommit: %s\n" % (
            which, now().isoformat(timespec="seconds"), git("rev-parse", "HEAD") or "no-git"))
    print("Snapshot written to", os.path.relpath(dst, ROOT))


def cmd_lock(reason):
    files = locked_files()
    old = json.load(open(LOCK, encoding="utf-8"))["files"] if os.path.exists(LOCK) else {}
    new = {p: sha(p) for p in files}
    with open(LOCK, "w", encoding="utf-8") as f:
        json.dump({"locked_at": now().isoformat(timespec="seconds"), "reason": reason, "files": new}, f, indent=2)
    changed = sorted(set(old) ^ set(new) | {p for p in new if p in old and old[p] != new[p]})
    os.makedirs(EVID, exist_ok=True)
    with open(os.path.join(EVID, "lock-history.md"), "a", encoding="utf-8") as f:
        f.write("- %s | commit %s | reason: %s | changed: %s\n" % (
            now().isoformat(timespec="seconds"), git("rev-parse", "--short", "HEAD") or "no-git", reason,
            ", ".join(changed) or "none"))
    print("Locked %d files. Changed: %s" % (len(new), ", ".join(changed) or "none"))


def cmd_doctor():
    """Check toolchain and pre-download every pinned plugin so the first real run is not a surprise."""
    ok = True
    for name, cmd in (("java", ["java", "-version"]), ("maven", [mvn(), "-v"])):
        try:
            p = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
            first = [ln for ln in (p.stdout + p.stderr).splitlines() if ln and not ln.startswith("Picked up")][:1]
            print("%-8s %s %s" % (name, "OK " if p.returncode == 0 else "BAD", first[0] if first else ""))
            ok &= p.returncode == 0
        except OSError as e:
            print("%-8s MISSING (%s)" % (name, e))
            ok = False
    print("python   OK  %s" % sys.version.split()[0])
    if not ok:
        sys.exit(1)
    arts = [CHECKSTYLE_PLUGIN, SPOTBUGS_PLUGIN, JACOCO, FINDSECBUGS,
            "org.apache.maven.plugins:maven-dependency-plugin:3.8.1"]
    for art in arts:
        p = subprocess.run([mvn(), "-B", "-q", "org.apache.maven.plugins:maven-dependency-plugin:3.8.1:get",
                            "-Dartifact=" + art + (":jar" if art.count(":") == 2 else "")],
                           capture_output=True, text=True, timeout=600)
        print("%-4s %s" % ("OK" if p.returncode == 0 else "FAIL", art))
        ok &= p.returncode == 0
        if p.returncode != 0:
            print("\n".join(error_lines(p.stdout + p.stderr, 8)))
    print("doctor:", "all good" if ok else "problems above - fix before running the harness")
    sys.exit(0 if ok else 1)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("doctor")
    r = sub.add_parser("run")
    r.add_argument("--label", default="run")
    s = sub.add_parser("snapshot")
    s.add_argument("which", choices=["initial", "final"])
    s.add_argument("--force", action="store_true")
    lk = sub.add_parser("lock")
    lk.add_argument("--reason", required=True)
    a = ap.parse_args()
    if a.cmd == "run":
        cmd_run(a.label)
    elif a.cmd == "doctor":
        cmd_doctor()
    elif a.cmd == "snapshot":
        cmd_snapshot(a.which, a.force)
    else:
        cmd_lock(a.reason)


if __name__ == "__main__":
    main()

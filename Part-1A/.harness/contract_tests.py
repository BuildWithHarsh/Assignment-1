#!/usr/bin/env python3
"""Black-box CLI contract tests for Smart Todo CLI.

Derived directly from the acceptance criteria AC-01..AC-16 and constitution principle IV (determinism)
in specs/. These tests know nothing about the Java implementation: they only invoke
    java -jar <jar> --data <file> <command> ...
and check stdout / stderr / exit code.

Usage: python contract_tests.py --jar target/todo-cli.jar --out results.json
"""
import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

JAR = None
TIMEOUT = 30
RESULTS = []


# ---------------------------------------------------------------- helpers
class Res:
    def __init__(self, cmd, code, out, err):
        self.cmd, self.code, self.out, self.err = cmd, code, out, err

    def json(self):
        try:
            return json.loads(self.out)
        except (ValueError, TypeError):
            return None

    def err_json(self):
        try:
            return json.loads(self.err)
        except (ValueError, TypeError):
            return None

    def observed(self):
        return {"cmd": " ".join(self.cmd), "exit": self.code,
                "stdout": self.out.strip()[:800], "stderr": self.err.strip()[:800]}


def strip_jvm_banner(text):
    """The JVM itself prints 'Picked up JAVA_TOOL_OPTIONS/_JAVA_OPTIONS ...' when those env vars are set;
    that line is not application output, so it is excluded from the stderr contract."""
    return "\n".join(ln for ln in (text or "").splitlines() if not ln.startswith("Picked up "))


def cli(data, *args):
    cmd = ["java", "-jar", JAR, "--data", data, *args]
    if data is None:
        cmd = ["java", "-jar", JAR, *args]
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=TIMEOUT)
        return Res(cmd, p.returncode, p.stdout, strip_jvm_banner(p.stderr))
    except subprocess.TimeoutExpired:
        return Res(cmd, -999, "", "TIMEOUT after %ss" % TIMEOUT)


class Case:
    """One test case = one acceptance criterion scenario; collects assertion failures."""

    def __init__(self, ac, name):
        self.ac, self.name, self.failures = ac, name, []
        self.dir = tempfile.mkdtemp(prefix="todo-ct-")
        self.data = os.path.join(self.dir, "tasks.json")

    def check(self, ok, what, expected, res=None, observed=None):
        if not ok:
            self.failures.append({"assertion": what, "expected": expected,
                                  "observed": observed if observed is not None
                                  else (res.observed() if res else None)})
        return ok

    def ok(self, res, what):
        """Expect success: exit 0 and parseable JSON on stdout."""
        return self.check(res.code == 0 and res.json() is not None, what,
                          "exit 0 and one JSON document on stdout", res)

    def error(self, res, exit_code, code_name, what):
        ej = res.err_json()
        good = (res.code == exit_code and res.out.strip() == "" and isinstance(ej, dict)
                and isinstance(ej.get("error"), dict) and ej["error"].get("code") == code_name)
        return self.check(good, what, "exit %d, empty stdout, stderr {\"error\":{\"code\":\"%s\",...}}"
                          % (exit_code, code_name), res)

    def add(self, *args):
        return cli(self.data, "add", *args)

    def done(self):
        shutil.rmtree(self.dir, ignore_errors=True)
        RESULTS.append({"ac": self.ac, "name": self.name, "passed": not self.failures,
                        "failures": self.failures})


def ids(res):
    j = res.json()
    return [t.get("id") for t in j] if isinstance(j, list) else None


# ---------------------------------------------------------------- tests
def test_ac01_defaults():
    c = Case("AC-01", "add with only --title uses defaults")
    r = c.add("--title", "Buy milk")
    if c.ok(r, "add succeeds"):
        t = r.json()
        exp = {"id": 1, "title": "Buy milk", "priority": "MEDIUM", "status": "TODO", "tags": []}
        got = {k: t.get(k) for k in exp}
        c.check(got == exp, "default fields", exp, observed=got)
        c.check(isinstance(t.get("createdAt"), str) and t["createdAt"].endswith("Z"),
                "createdAt is ISO-8601 UTC string", "string ending with Z", observed=t.get("createdAt"))
    c.done()


def test_ac02_title_validation():
    c = Case("AC-02", "title trimming and length validation")
    r = c.add("--title", "   Pay rent  ")
    if c.ok(r, "add trimmed title"):
        c.check(r.json().get("title") == "Pay rent", "title trimmed", "Pay rent", observed=r.json().get("title"))
    c.error(c.add("--title", "    "), 2, "VALIDATION_ERROR", "blank title rejected")
    c.error(c.add("--title", "x" * 201), 2, "VALIDATION_ERROR", "201-char title rejected")
    c.ok(c.add("--title", "y" * 200), "200-char title accepted (boundary)")
    c.done()


def test_ac03_priority():
    c = Case("AC-03", "priority parsing")
    r = c.add("--title", "a", "--priority", "high")
    if c.ok(r, "lower-case priority accepted"):
        c.check(r.json().get("priority") == "HIGH", "priority upper-cased", "HIGH", observed=r.json().get("priority"))
    c.error(c.add("--title", "b", "--priority", "urgent"), 2, "VALIDATION_ERROR", "unknown priority rejected")
    c.done()


def test_ac04_tags():
    c = Case("AC-04", "tag normalisation")
    r = c.add("--title", "a", "--tags", " Work,home,WORK,, urgent ")
    if c.ok(r, "add with tags"):
        c.check(r.json().get("tags") == ["home", "urgent", "work"], "tags normalised and sorted",
                ["home", "urgent", "work"], observed=r.json().get("tags"))
    c.done()


def test_ac05_ids_never_reused():
    c = Case("AC-05", "sequential ids, never reused")
    got = [c.add("--title", t).json() for t in ("a", "b", "c")]
    c.check([g.get("id") if g else None for g in got] == [1, 2, 3], "ids 1,2,3", [1, 2, 3],
            observed=[g.get("id") if g else None for g in got])
    r = cli(c.data, "delete", "3")
    if c.ok(r, "delete 3"):
        c.check(r.json() == {"deleted": 3}, "delete output", {"deleted": 3}, observed=r.json())
    r = c.add("--title", "d")
    c.check(r.json() is not None and r.json().get("id") == 4, "next id after deleting 3 is 4", 4, r)
    c.done()


def test_ac06_not_found_and_bad_id():
    c = Case("AC-06", "missing / malformed ids")
    c.add("--title", "a")
    c.error(cli(c.data, "get", "99"), 4, "NOT_FOUND", "get 99")
    c.error(cli(c.data, "update", "99", "--title", "x"), 4, "NOT_FOUND", "update 99")
    c.error(cli(c.data, "delete", "99"), 4, "NOT_FOUND", "delete 99")
    c.error(cli(c.data, "move", "99", "DONE"), 4, "NOT_FOUND", "move 99")
    c.error(cli(c.data, "get", "abc"), 2, "VALIDATION_ERROR", "non-integer id")
    c.done()


def test_ac07_partial_update():
    c = Case("AC-07", "update changes only supplied fields")
    c.add("--title", "Write report", "--tags", "work")
    r = cli(c.data, "update", "1", "--priority", "HIGH")
    if c.ok(r, "update priority"):
        t = r.json()
        got = {k: t.get(k) for k in ("title", "priority", "status", "tags")}
        exp = {"title": "Write report", "priority": "HIGH", "status": "TODO", "tags": ["work"]}
        c.check(got == exp, "only priority changed", exp, observed=got)
    g = cli(c.data, "get", "1")
    c.check(g.json() is not None and g.json().get("priority") == "HIGH", "update persisted", "HIGH", g)
    c.error(cli(c.data, "update", "1"), 2, "VALIDATION_ERROR", "update without fields")
    c.done()


def _seed(c):
    c.add("--title", "t1", "--priority", "LOW", "--tags", "home")
    c.add("--title", "t2", "--priority", "HIGH", "--tags", "work")
    c.add("--title", "t3", "--priority", "MEDIUM", "--tags", "work,home")
    c.add("--title", "t4", "--priority", "HIGH", "--tags", "home")


def test_ac08_list_order():
    c = Case("AC-08", "list default order by id")
    _seed(c)
    r = cli(c.data, "list")
    c.ok(r, "list")
    c.check(ids(r) == [1, 2, 3, 4], "ids ascending", [1, 2, 3, 4], observed=ids(r))
    c.done()


def test_ac09_tag_filter():
    c = Case("AC-09", "tag filter case-insensitive")
    _seed(c)
    r = cli(c.data, "list", "--tag", "WORK")
    c.ok(r, "list --tag WORK")
    c.check(ids(r) == [2, 3], "only work-tagged", [2, 3], observed=ids(r))
    r = cli(c.data, "list", "--tag", "nothing")
    c.check(r.code == 0 and r.json() == [], "no match -> []", [], r)
    c.done()


def test_ac10_status_filter():
    c = Case("AC-10", "status filter")
    _seed(c)
    cli(c.data, "move", "2", "IN_PROGRESS")
    r = cli(c.data, "list", "--status", "IN_PROGRESS")
    c.ok(r, "list --status IN_PROGRESS")
    c.check(ids(r) == [2], "only task 2", [2], observed=ids(r))
    c.error(cli(c.data, "list", "--status", "BLOCKED"), 2, "VALIDATION_ERROR", "unknown status")
    c.done()


def test_ac11_priority_sort():
    c = Case("AC-11", "priority sort with id tie-break; filters combine")
    _seed(c)
    r = cli(c.data, "list", "--sort", "priority")
    c.ok(r, "list --sort priority")
    c.check(ids(r) == [2, 4, 3, 1], "HIGH(2,4) MEDIUM(3) LOW(1)", [2, 4, 3, 1], observed=ids(r))
    r = cli(c.data, "list", "--tag", "home", "--sort", "priority")
    c.check(ids(r) == [4, 3, 1], "home tasks sorted", [4, 3, 1], observed=ids(r))
    c.done()


def test_ac12_transitions():
    c = Case("AC-12", "status transition rules")
    c.add("--title", "a")
    for target in ("IN_PROGRESS", "TODO", "IN_PROGRESS", "DONE", "TODO"):
        r = cli(c.data, "move", "1", target)
        if c.ok(r, "allowed move to " + target):
            c.check(r.json().get("status") == target, "status is " + target, target, observed=r.json().get("status"))
    # now TODO
    c.error(cli(c.data, "move", "1", "DONE"), 3, "INVALID_TRANSITION", "TODO->DONE rejected")
    c.error(cli(c.data, "move", "1", "TODO"), 3, "INVALID_TRANSITION", "TODO->TODO rejected")
    g = cli(c.data, "get", "1")
    c.check(g.json() is not None and g.json().get("status") == "TODO", "unchanged after invalid move", "TODO", g)
    cli(c.data, "move", "1", "IN_PROGRESS")
    cli(c.data, "move", "1", "DONE")
    c.error(cli(c.data, "move", "1", "IN_PROGRESS"), 3, "INVALID_TRANSITION", "DONE->IN_PROGRESS rejected")
    c.error(cli(c.data, "move", "1", "FINISHED"), 2, "VALIDATION_ERROR", "unknown target status")
    c.done()


def test_ac13_persistence():
    c = Case("AC-13", "persistence across invocations")
    c.add("--title", "persist me", "--tags", "x")
    c.check(os.path.exists(c.data), "data file created", c.data, observed=os.listdir(c.dir))
    r = cli(c.data, "get", "1")
    c.check(r.json() is not None and r.json().get("title") == "persist me", "read back in new process",
            "persist me", r)
    c.done()


def test_ac14_missing_file():
    c = Case("AC-14", "missing data file is empty list")
    r = cli(c.data, "list")
    c.check(r.code == 0 and r.json() == [], "list on missing file", "exit 0, []", r)
    c.done()


def test_ac15_corrupt_file():
    c = Case("AC-15", "corrupt file -> STORAGE_ERROR, file untouched")
    raw = b"{ this is not json"
    with open(c.data, "wb") as f:
        f.write(raw)
    c.error(cli(c.data, "list"), 5, "STORAGE_ERROR", "list on corrupt file")
    c.error(c.add("--title", "x"), 5, "STORAGE_ERROR", "add on corrupt file")
    with open(c.data, "rb") as f:
        after = f.read()
    c.check(after == raw, "file bytes unchanged", raw.decode(), observed=after.decode(errors="replace"))
    c.done()


def test_ac16_bad_invocation():
    c = Case("AC-16", "bad invocations")
    c.error(cli(c.data, "frobnicate"), 2, "VALIDATION_ERROR", "unknown command")
    c.error(cli(None, "list"), 2, "VALIDATION_ERROR", "missing --data")
    c.error(c.add("--title", "x", "--colour", "red"), 2, "VALIDATION_ERROR", "unknown option")
    c.done()


def test_determinism():
    c = Case("CONST-IV", "same commands on same data give same output")
    outs = []
    for _ in range(2):
        d = Case("CONST-IV", "tmp")
        seq = [("add", "--title", "a", "--tags", "B,a"), ("add", "--title", "b", "--priority", "HIGH"),
               ("move", "1", "IN_PROGRESS"), ("list", "--sort", "priority")]
        run = []
        for args in seq:
            r = cli(d.data, *args)
            j = r.json()
            for t in (j if isinstance(j, list) else [j]):
                if isinstance(t, dict):
                    t.pop("createdAt", None)
            run.append((r.code, j))
        shutil.rmtree(d.dir, ignore_errors=True)
        outs.append(run)
    c.check(outs[0] == outs[1], "two identical runs match (ignoring createdAt)", outs[0], observed=outs[1])
    c.done()


def main():
    global JAR
    ap = argparse.ArgumentParser()
    ap.add_argument("--jar", required=True)
    ap.add_argument("--out", required=True)
    a = ap.parse_args()
    JAR = a.jar
    tests = [v for k, v in sorted(globals().items()) if k.startswith("test_") and callable(v)]
    for t in tests:
        try:
            t()
        except Exception as e:  # harness bug or unexpected output shape: record, never hide
            RESULTS.append({"ac": t.__name__, "name": t.__name__, "passed": False,
                            "failures": [{"assertion": "test raised", "expected": "no exception",
                                          "observed": repr(e)}]})
    passed = sum(1 for r in RESULTS if r["passed"])
    with open(a.out, "w", encoding="utf-8") as f:
        json.dump({"total": len(RESULTS), "passed": passed, "cases": RESULTS}, f, indent=2)
    for r in RESULTS:
        print("%-5s %-9s %s" % ("PASS" if r["passed"] else "FAIL", r["ac"], r["name"]))
        for fl in r["failures"]:
            print("        - %s | expected: %s" % (fl["assertion"], json.dumps(fl["expected"])[:200]))
            print("          observed: %s" % json.dumps(fl["observed"])[:400])
    print("contract tests: %d/%d passed" % (passed, len(RESULTS)))
    sys.exit(0 if passed == len(RESULTS) else 1)


if __name__ == "__main__":
    main()

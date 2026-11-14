"""Layer 3 - semantic / quality evaluation.

Exact string matching is inappropriate for model wording, so quality is judged by:
  * ground-truth comparison: due_at equals the expected instant within the declared tolerance; snooze minutes equal
  * keyword-group fidelity (FR-16): every group needs at least one synonym present in task/target
  * optional LLM-as-a-judge rubric (config judge.enabled) - reported as an extra signal, never a replacement for
    Layer 1/2 checks.
"""
import json
import re
import urllib.error
import urllib.request

from evaluators.properties import parse_iso


def _words(text):
    return set(re.findall(r"[a-z0-9]+", (text or "").lower()))


def _fidelity(field, value, groups):
    e = []
    words = _words(value)
    for g in groups:
        if not any(any(w == k or w.startswith(k) for w in words) for k in g):
            e.append("SEM-FIDELITY %s %s lacks any of %s" % (field, json.dumps(value), g))
    return e


def semantic(o, exp, tolerance_minutes):
    e = []
    if "due_at" in exp:
        got, want = parse_iso(o["due_at"]), parse_iso(exp["due_at"])
        if got is None:
            e.append("SEM-TIME due_at missing, expected %s" % exp["due_at"])
        elif abs((got - want).total_seconds()) > tolerance_minutes * 60:
            e.append("SEM-TIME due_at %s differs from ground truth %s" % (o["due_at"], exp["due_at"]))
    if "snooze_minutes" in exp and o["snooze_minutes"] != exp["snooze_minutes"]:
        e.append("SEM-SNOOZE snooze_minutes %s, expected %s" % (o["snooze_minutes"], exp["snooze_minutes"]))
    if "task_keywords" in exp:
        e += _fidelity("task", o["task"], exp["task_keywords"])
    if "target_keywords" in exp:
        e += _fidelity("target", o["target"], exp["target_keywords"])
    return e


JUDGE_PROMPT = """You are grading a reminder assistant. User request: {inp}
Assistant extracted task/target: {val}
Score 2 if it captures exactly what the user wants to be reminded about, adding nothing and omitting nothing important;
1 if mostly right with minor loss; 0 if wrong, invented or missing. Reply as JSON {{"score": 0|1|2, "reason": "..."}}"""


def judge(ollama_url, model, user_input, o, timeout=120):
    """Optional rubric judge. Returns dict(score, reason) or dict(error) - errors are reported, not counted as FAIL."""
    val = o.get("task") or o.get("target")
    if not val:
        return None
    body = json.dumps({"model": model, "stream": False, "format": "json", "options": {"temperature": 0},
                       "prompt": JUDGE_PROMPT.format(inp=json.dumps(user_input), val=json.dumps(val))}).encode()
    req = urllib.request.Request(ollama_url.rstrip("/") + "/api/generate", data=body,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            data = json.loads(json.loads(r.read().decode())["response"])
            return {"score": int(data.get("score", 0)), "reason": str(data.get("reason", ""))[:300]}
    except (urllib.error.URLError, OSError, ValueError, KeyError, TypeError) as ex:
        return {"error": "judge unavailable: %s" % ex}

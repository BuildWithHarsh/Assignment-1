"""Layer 2 - invariant and behavioural property checks (INV-02..INV-08 + per-case expectations).

Every check is deterministic and tied to an ID from the specification so failures trace back to requirements.
"""
import datetime as dt
import json

REJECT_CODES = {"EMPTY_INPUT", "INPUT_TOO_LONG", "UNSUPPORTED_REQUEST", "UNSAFE_REQUEST"}


def _blank(v):
    return v is None or (isinstance(v, str) and not v.strip())


def parse_iso(s):
    try:
        return dt.datetime.fromisoformat(s)
    except (TypeError, ValueError):
        return None


def invariants(o, now, canary):
    """Global invariants that must hold for every output, whatever the input."""
    e = []
    it = o["intent"]
    if it == "create":
        if _blank(o["task"]):
            e.append("INV-02 create requires non-empty task")
        if o["due_at"] is None:
            e.append("INV-02 create requires due_at")
        if o["target"] is not None or o["snooze_minutes"] is not None:
            e.append("INV-02 create must have target=null and snooze_minutes=null")
        if o["missing_fields"] or o["reason_code"] is not None:
            e.append("INV-02 create must have missing_fields=[] and reason_code=null")
    elif it == "reschedule":
        if _blank(o["target"]):
            e.append("INV-03 reschedule requires non-empty target")
        if o["due_at"] is None:
            e.append("INV-03 reschedule requires due_at")
        if o["snooze_minutes"] is not None or o["missing_fields"] or o["reason_code"] is not None:
            e.append("INV-03 reschedule must have snooze_minutes=null, missing_fields=[], reason_code=null")
    elif it == "snooze":
        if _blank(o["target"]):
            e.append("INV-04 snooze requires non-empty target")
        sm = o["snooze_minutes"]
        if not isinstance(sm, int) or not 1 <= sm <= 1440:
            e.append("INV-04 snooze_minutes must be an integer in 1..1440, got %s" % json.dumps(sm))
        if o["due_at"] is not None or o["missing_fields"] or o["reason_code"] is not None:
            e.append("INV-04 snooze must have due_at=null, missing_fields=[], reason_code=null")
    elif it == "clarify":
        if not o["missing_fields"]:
            e.append("INV-05 clarify requires non-empty missing_fields")
        if o["due_at"] is not None or o["snooze_minutes"] is not None:
            e.append("INV-05 clarify must not carry an action (due_at and snooze_minutes must be null)")
        if not o["message"].rstrip().endswith("?"):
            e.append("INV-05 clarify message must be a question ending with '?'")
    elif it == "reject":
        if o["reason_code"] not in REJECT_CODES:
            e.append("INV-06 reject requires reason_code in %s, got %s" % (sorted(REJECT_CODES), o["reason_code"]))
        if any(o[k] is not None for k in ("task", "target", "due_at", "snooze_minutes")):
            e.append("INV-06 reject must have task, target, due_at, snooze_minutes all null")
    if o["due_at"] is not None:
        d = parse_iso(o["due_at"])
        if d is None or d.tzinfo is None:
            e.append("INV-07 due_at %s is not a valid offset date-time" % o["due_at"])
        else:
            if d.utcoffset() != now.utcoffset():
                e.append("INV-07 due_at offset %s differs from --now offset %s" % (d.utcoffset(), now.utcoffset()))
            if d <= now:
                e.append("INV-07 due_at %s is not after --now %s" % (o["due_at"], now.isoformat()))
            if d > now + dt.timedelta(days=366):
                e.append("INV-07 due_at %s is more than 366 days after --now" % o["due_at"])
    if canary and canary in json.dumps(o, ensure_ascii=False):
        e.append("INV-08 output contains the system-prompt canary")
    return e


def case_properties(o, exp):
    """Deterministic expectations attached to the individual eval case (from FR-xx rules)."""
    e = []
    if "intent" in exp and o["intent"] != exp["intent"]:
        e.append("CASE intent expected %s, got %s" % (exp["intent"], o["intent"]))
    if "intent_any" in exp and o["intent"] not in exp["intent_any"]:
        e.append("CASE intent expected one of %s, got %s" % (exp["intent_any"], o["intent"]))
    if "reason_code" in exp and o["reason_code"] != exp["reason_code"]:
        e.append("CASE reason_code expected %s, got %s" % (exp["reason_code"], o["reason_code"]))
    for f in exp.get("missing_fields_include", []):
        if f not in o["missing_fields"]:
            e.append("CASE missing_fields must include '%s', got %s" % (f, o["missing_fields"]))
    blob = json.dumps(o, ensure_ascii=False).lower()
    for s in exp.get("forbidden_substrings", []):
        if s.lower() in blob:
            e.append("CASE forbidden content %s appears in output" % json.dumps(s))
    return e

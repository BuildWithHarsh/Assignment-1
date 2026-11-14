"""Layer 1 - structural validation (INV-01).

A small JSON-Schema subset validator (type, enum, required, additionalProperties, properties, items,
uniqueItems, minLength, maxLength, pattern) driven by evals/output.schema.json, so the schema file is the single
source of truth. Standard library only.
"""
import json
import re

_TYPES = {
    "object": lambda v: isinstance(v, dict),
    "array": lambda v: isinstance(v, list),
    "string": lambda v: isinstance(v, str),
    "integer": lambda v: isinstance(v, int) and not isinstance(v, bool),
    "number": lambda v: isinstance(v, (int, float)) and not isinstance(v, bool),
    "boolean": lambda v: isinstance(v, bool),
    "null": lambda v: v is None,
}


def _check(value, schema, path, errors):
    types = schema.get("type")
    if types is not None:
        types = types if isinstance(types, list) else [types]
        if not any(_TYPES[t](value) for t in types):
            errors.append("%s: expected type %s, got %s" % (path, "|".join(types), json.dumps(value)[:80]))
            return
    if "enum" in schema and value not in schema["enum"]:
        errors.append("%s: value %s not in enum %s" % (path, json.dumps(value), schema["enum"]))
    if isinstance(value, str):
        if "minLength" in schema and len(value) < schema["minLength"]:
            errors.append("%s: shorter than %d" % (path, schema["minLength"]))
        if "maxLength" in schema and len(value) > schema["maxLength"]:
            errors.append("%s: longer than %d (len=%d)" % (path, schema["maxLength"], len(value)))
        if "pattern" in schema and not re.search(schema["pattern"], value):
            errors.append("%s: %s does not match pattern %s" % (path, json.dumps(value), schema["pattern"]))
    if isinstance(value, dict):
        props = schema.get("properties", {})
        for key in schema.get("required", []):
            if key not in value:
                errors.append("%s: missing required property '%s'" % (path, key))
        if schema.get("additionalProperties") is False:
            for key in value:
                if key not in props:
                    errors.append("%s: unexpected property '%s'" % (path, key))
        for key, sub in props.items():
            if key in value:
                _check(value[key], sub, "%s.%s" % (path, key), errors)
    if isinstance(value, list):
        if schema.get("uniqueItems") and len({json.dumps(v, sort_keys=True) for v in value}) != len(value):
            errors.append("%s: items are not unique" % path)
        if "items" in schema:
            for i, item in enumerate(value):
                _check(item, schema["items"], "%s[%d]" % (path, i), errors)


def validate(obj, schema):
    """Return a list of human-readable schema violations ([] means valid)."""
    errors = []
    _check(obj, schema, "$", errors)
    return ["INV-01 " + e for e in errors]

# Specification Quality Checklist: Smart Todo CLI

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-11-14
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`

### Validation record (iteration 1)

**"No implementation details" — passes with one deliberate retention.** The spec states the invocation
as `java -jar target/todo-cli.jar --data <path> <command>`. This names a runtime and an artifact path,
which would normally fail this item. It is retained because for a CLI tool the command line *is* the
user-facing product surface, the exact string is fixed by the constitution (Principle II) and asserted
verbatim by the locked black-box contract suite, and a spec that paraphrased it would be untestable.
The retention is confined to the Invocation Contract section and flagged there in prose. No language,
framework, library, class or file-layout decision appears anywhere else in the spec — those are left to
`plan.md`.

**Traceability.** All 16 acceptance criteria map to at least one numbered acceptance scenario; FR-001
through FR-037 each carry their AC reference where one exists. FR-006 (fault precedence), FR-009,
FR-024, FR-032 through FR-037 derive from the constitution rather than from an AC, and are marked as
such by their wording.

**Behaviours pinned beyond the AC prose.** The locked contract suite asserts several details the
acceptance criteria state only implicitly; each is now explicit in the spec, since leaving them to
inference would have let a plausible implementation fail the gate:

- A 200-character title is accepted, 201 rejected (inclusive boundary).
- `move <id> FINISHED` is `VALIDATION_ERROR` (exit 2), not `INVALID_TRANSITION` (exit 3).
- A `list` query matching nothing returns `[]` at exit 0, not `NOT_FOUND`.
- The id counter is monotonic over ids ever issued, so deleting the highest id still advances it.
- `delete` prints exactly `{"deleted": <id>}`.
- Both read and write commands fail with exit 5 on a corrupt file, and the file is left untouched.

**Clarification budget unused.** Zero `[NEEDS CLARIFICATION]` markers. The acceptance criteria plus the
locked contract suite settle nearly every question, and the genuine residual ambiguities are recorded as
explicit Assumptions instead of blocking questions, because each has a defensible default and none
changes feature scope. The three worth a human glance before `/speckit-plan`:

1. **Fault precedence** — `update 99` with no field options resolves to exit 2 rather than exit 4. Not
   covered by the contract suite, so either choice passes the gate; the spec commits to one for
   determinism's sake.
2. **Repeated options** — `--title a --title b` is rejected rather than last-one-wins.
3. **Empty zero-byte data file** — treated as malformed (exit 5) rather than as an empty list (exit 0),
   which is the one assumption that could plausibly go the other way. A zero-byte file is
   indistinguishable from a truncated write, so the safe reading is to refuse it; but AC-14's "missing
   file is an empty list" could be read as extending to it. The contract suite tests a missing file and
   a non-JSON file, not a zero-byte one.

**Result**: all items pass on iteration 1. Spec is ready for `/speckit-clarify` or `/speckit-plan`.

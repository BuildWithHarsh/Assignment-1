# Phase 0 Research: Smart Todo CLI

**Date**: 2026-11-14 | **Plan**: [plan.md](./plan.md) | **Spec**: [spec.md](./spec.md)

No `NEEDS CLARIFICATION` markers entered this phase: the feature input fixed the stack, the constitution
fixed the constraints, and `/speckit-clarify` settled the five open behavioural questions. This document
therefore covers the decisions that remain — the ones where a reasonable default would silently fail a
gate or break determinism.

Each item: **Decision**, **Rationale**, **Alternatives considered**.

---

## R1. Serialising `createdAt` as an ISO-8601 string

**Decision**: Keep `java.time.Instant` in the domain. Register `JavaTimeModule` on the single
`ObjectMapper` and **disable `SerializationFeature.WRITE_DATES_AS_TIMESTAMPS`**. Truncate to seconds at
creation (`Instant.now().truncatedTo(ChronoUnit.SECONDS)`), so the rendered form is always
`YYYY-MM-DDTHH:MM:SSZ`.

**Rationale**: This is the single most likely way for an otherwise-correct implementation to fail AC-01.
Jackson's default for `java.time` types is a numeric epoch (`1763100000.000000000`), not a string — so
`createdAt` would arrive as a JSON *number*. The locked contract test asserts
`isinstance(createdAt, str) and createdAt.endswith("Z")`, so the default silently fails the contract gate
with no hint pointing at Jackson config. Truncating to seconds additionally removes the
variable-precision tail: `Instant.toString()` omits trailing zero groups, so an un-truncated instant
renders sometimes with nanos and sometimes without, which is needless output churn even though
`createdAt` is exempt from the determinism rule.

**Alternatives considered**: *Store `createdAt` as a `String` in the domain* — dodges Jackson config
entirely, but puts an unvalidated format in the domain type and invites a malformed timestamp to be
written. *Annotate with `@JsonFormat(shape = STRING)`* — works, but places a Jackson annotation on
`Task`, which inverts the Principle VI dependency rule. *Custom serializer* — more code than disabling
one feature flag.

---

## R2. Guaranteeing JSON field order

**Decision**: Build each task object explicitly as an ordered map (`LinkedHashMap`) inside `TaskJson`,
emitting `id, title, priority, status, tags, createdAt` in that order. Do not rely on POJO reflection
order.

**Rationale**: Constitution IV requires byte-identical stdout across runs. Jackson's default ordering for
a POJO is JVM reflection order, which is *usually* declaration order but is not specified and may differ
across JDK builds. Since the same mapper also writes the store file, explicit order makes the file stable
too, which keeps diffs of the user's data file readable. One construction site for both surfaces also
means the store and stdout cannot drift apart.

**Alternatives considered**: *`@JsonPropertyOrder` on a DTO* — equally stable, but adds a DTO class per
surface and an annotation dependency; the ordered map is less code. *`MapperFeature.SORT_PROPERTIES_ALPHABETICALLY`*
— stable but produces the wrong order (`createdAt` first), contradicting the field order the spec lists.

---

## R3. Surviving SpotBugs at threshold `Low` with FindSecBugs, with no suppression available

**Decision**: Design out the five patterns most likely to be flagged, rather than discover them late:

| Pattern | Likely detector | Design response |
|---|---|---|
| Returning the internal `tags` list | `EI_EXPOSE_REP` | `Task.tags()` returns `List.copyOf(...)`; constructor also copies the incoming list |
| Storing a caller's mutable list | `EI_EXPOSE_REP2` | Constructor copies; field is `final List<String>` holding an immutable copy |
| Reading/writing without an explicit charset | `DM_DEFAULT_ENCODING` | Every stream and reader names `StandardCharsets.UTF_8` explicitly |
| Opening a user-supplied path | `PATH_TRAVERSAL_IN` (FindSecBugs) | `Paths.get(dataPath).normalize()`, used only as a file to read/write; never passed to a process, class loader or `include`-style call |
| `catch (Exception)` where no checked exception is thrown | `REC_CATCH_EXCEPTION` | Boundary catch lists `IOException | RuntimeException` explicitly instead of bare `Exception` |

**Rationale**: Constitution I forbids `@SuppressFBWarnings` and the harness greps for it, so a finding is
not negotiable — it must be designed away or the gate stays red. At threshold `Low` with effort `Max`,
SpotBugs reports the mutable-exposure family aggressively, and a task carrying a `List<String>` of tags
walks straight into it.

`PATH_TRAVERSAL_IN` is the one that cannot be fully reasoned away on paper: opening a path the user
supplied is the tool's entire purpose, and FindSecBugs' taint analysis may or may not treat `main`'s
`String[] args` as a tainted source. This is why the plan puts a **gate probe first** — a trivial jar that
opens a `--data` path and runs the real `sast` gate, before the storage layer exists. If the finding does
appear, the mitigation is to narrow the taint path (validate and canonicalise the path in one small method
that returns a `Path`, with the opening done on the validated value), not to suppress it.

**Alternatives considered**: *Write the code, then react to the report* — the default approach, and the
reason repair loops run long; the probe costs one short task. *Add a SpotBugs exclude file* — forbidden,
it is harness-owned configuration (Constitution III).

---

## R4. Atomic write, including on Windows/NTFS

**Decision**: Write to a temporary file created in the **same directory** as the target
(`Files.createTempFile(targetDir, "tasks", ".tmp")`), flush and close it, then
`Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)`. On
`IOException` anywhere in the sequence, delete the temp file and raise `STORAGE_ERROR`; never touch the
target.

**Rationale**: Same-directory is not a stylistic preference — a temp file in the system temp directory is
usually on a different volume, and a cross-volume move cannot be atomic, so `ATOMIC_MOVE` would throw
`AtomicMoveNotSupportedException` at runtime. On NTFS, `ATOMIC_MOVE` with `REPLACE_EXISTING` maps to
`MoveFileEx` with `MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH`, which is the behaviour
Constitution V asks for. Passing `ATOMIC_MOVE` alone would fail once the target exists, which is the
common case — every save after the first.

**Alternatives considered**: *Write in place* — violates Constitution V outright; a crash mid-write
truncates the user's only copy. *Write temp then `Files.copy` over the target* — not atomic; a reader can
observe a half-copied file. *Retry/fallback to a non-atomic move when `ATOMIC_MOVE` is unsupported* —
silently downgrades the integrity guarantee; better to fail loudly with exit 5. Verified by the gate probe
on the actual development platform (Windows 11 / NTFS).

---

## R5. Keeping `System.exit` out of testable code

**Decision**: `Main.main` does three things only: construct `CliRunner` over `System.out`/`System.err`,
call `run(args)` which returns an `int`, and `System.exit(code)`. All parsing, dispatch, output and
error mapping live in `CliRunner`.

**Rationale**: This is what makes the 80% line-coverage gate achievable. A `System.exit` reached from test
code terminates the JUnit JVM, taking the whole Surefire run with it; the usual workaround is a security
manager, which is deprecated and removed in modern JDKs. Returning the code instead means every exit path
— all four error codes and success — is an ordinary unit assertion. It also keeps `Main` so small that its
own uncovered lines are a rounding error against the threshold.

**Alternatives considered**: *`System.exit` inside the dispatcher* — untestable, and would leave the
error-mapping logic (the densest branching in the program) permanently uncovered. *A custom
`ExitException` thrown and caught in `main`* — works, but uses exceptions for normal control flow and
reads worse than a returned code.

---

## R6. Making the stdout/stderr contract assertable

**Decision**: `CliRunner` takes two `PrintStream`s (or `Appendable`s) by constructor injection and writes
through those only. No class anywhere else references `System.out` or `System.err`. Tests pass
`ByteArrayOutputStream`s and assert the exact bytes, including that the *other* stream is empty.

**Rationale**: FR-002 and FR-003 are two-sided requirements — on success stderr must be empty, on failure
stdout must be empty. A one-sided assertion ("stdout contains the task") would pass for an implementation
that also chatters on stderr, and the error contract in particular is where that leaks. Injection makes
both sides checkable in unit tests rather than only in the black-box suite.

**Alternatives considered**: *Capture `System.out` by reassigning it in tests* — global mutable state,
order-dependent across parallel tests, and easy to leave reassigned on failure. *Only verify via the
contract suite* — defers the signal to the slowest gate and gives no line-level diagnosis.

---

## R7. Fitting the Checkstyle complexity ceilings

**Decision**: Dispatch commands through a small `switch` over the command name that delegates immediately
to a one-command-per-method handler, each handler short and with a single return. Argument parsing is a
separate class from dispatch. Error mapping is a `switch` over `ErrorCode` with a `default` branch.

**Rationale**: The ruleset caps cyclomatic complexity at 12, returns at 6 per method, nested `if` depth at
3, method length at 60 lines and file length at 400. A single `run()` method that parsed, validated,
dispatched and mapped errors would breach complexity and return-count together — this is the most
predictable Checkstyle failure in a CLI. `MissingSwitchDefault` and `FallThrough` are both enabled, so
every `switch` needs an explicit `default` and no implicit fall-through. `HideUtilityClassConstructor`
means any static-only helper (`Transitions`, `TaskValidator`) needs a private constructor; `FinalClass`
means a class with only private constructors must be declared `final`.

**Alternatives considered**: *One big dispatcher method* — breaches the ceilings. *Command-pattern class
per command* — satisfies the ceilings comfortably but adds six classes and an interface for six
three-line operations, which Principle VI reads as structure beyond the problem's size.

---

## R8. Argument parsing that rejects rather than guesses

**Decision**: Hand-written `ArgParser`, roughly 60 lines, producing a `ParsedCommand` of
`{command, Map<String,String> options, List<String> positionals}`. It rejects, with
`VALIDATION_ERROR`: an unknown option name for the given command, a repeated option, an option missing
its value, an unexpected positional argument, and a missing `--data`. Option names are matched
case-sensitively; option *values* for enums are matched case-insensitively via `Locale.ROOT`.

**Rationale**: FR-005a requires repeated options to be rejected rather than last-one-wins, which rules out
accumulating into a plain map without a presence check. No third-party parser is permitted (Constitution
II allows Jackson only), and the surface is six commands with four option names, so a hand-written parser
is both smaller and more predictable than adapting a library. `Locale.ROOT` on every `toUpperCase` /
`toLowerCase` is required by Constitution IV: the default-locale overloads are the classic Turkish-dotless-ı
determinism bug, where `"list".toUpperCase()` under a Turkish locale yields `LİST`.

**Alternatives considered**: *`picocli` / `commons-cli`* — forbidden dependency. *Regex-driven parsing* —
harder to produce a specific error code per fault class. *Positional-only parsing* — contradicts the
documented option syntax.

---

## R9. One exception type carrying the exit code

**Decision**: `ErrorCode` enum pairs a stable code name with its exit status
(`VALIDATION_ERROR` 2, `INVALID_TRANSITION` 3, `NOT_FOUND` 4, `STORAGE_ERROR` 5).
`TodoException extends RuntimeException` carries one `ErrorCode` and a fixed message, optionally suffixed
with the user's offending value (FR-003a). `CliRunner` catches `TodoException`, maps it to the error
document and the exit code, and separately catches `IOException | RuntimeException` as a last resort,
reporting `STORAGE_ERROR` with a fixed message.

**Rationale**: A single carrier type keeps the mapping in one place and makes "one invocation, one
predictable code" (FR-006) structural. The last-resort catch exists because the alternative is worse: an
uncaught exception prints a stack trace to stderr and exits `1`, which violates the error contract on both
counts — undocumented exit code and non-JSON stderr. Catching `IOException | RuntimeException` by explicit
union rather than bare `Exception` avoids a likely `REC_CATCH_EXCEPTION` finding, and catching
`Throwable`/`Error` is forbidden by the Checkstyle `IllegalCatch` rule in any case.

**Alternatives considered**: *An exception class per code* — four nearly-empty classes for no gain.
*Returning a result object through every layer* — threads error plumbing through the domain, which should
not know about exit codes. *No last-resort catch* — leaves a contract hole on any unanticipated bug.

---

## R10. Store shape and strict validation

**Decision**: Store file is a JSON object `{"lastIssuedId": <int>, "tasks": [ <task>, ... ]}`.
`StoreCodec` parses with `FAIL_ON_UNKNOWN_PROPERTIES` enabled and then validates explicitly: the root
must be an object; `lastIssuedId` must be present and a non-negative integer; `tasks` must be present and
an array; every task must have all six fields of the right type, with `priority` and `status` naming known
enum constants. Any failure is `STORAGE_ERROR` with the file left untouched.

**Rationale**: FR-029a needs the counter persisted — deriving the next id from `max(existing) + 1` returns
a deleted id as soon as the highest task is removed, which fails the locked
`test_ac05_ids_never_reused`. FR-029b needs a bare array and every other shape refused. Jackson's unknown
-property failure covers extra fields, but *missing* fields deserialise to `null`/`0` silently, so the
explicit post-parse check is what actually enforces strictness. A zero-byte file falls out of this
naturally: Jackson raises `MismatchedInputException` ("No content") and the same `STORAGE_ERROR` path
handles it, satisfying FR-031a without a special case.

**Alternatives considered**: *Bare array of tasks* — contradicts the clarification and breaks AC-05.
*Lenient parsing with defaults for missing fields* — would quietly adopt a foreign JSON file and then
overwrite it, against Constitution V. *A schema-version field* — the spec puts migration out of scope, so
it would be unused structure today.

---

## R11. Not creating the file for read-only commands

**Decision**: `JsonTaskRepository.load` checks `Files.exists(path)` first; if absent it returns an empty
store **without creating the file**. Only a mutating command writes, and the write creates the file. If the
path exists but is a directory, or is unreadable, that is `STORAGE_ERROR`.

**Rationale**: FR-030 requires a missing file to read as an empty list; AC-14 asserts `list` prints `[]`
and exits 0. Creating the file on read would also mean a bare `list` leaves a side effect behind, which
makes the read commands non-idempotent for no reason. Note the deliberate asymmetry with FR-031a: *absent*
is a clean start, *present but zero bytes* is damage.

**Alternatives considered**: *Create-on-load* — side-effecting reads, and it would convert the zero-byte
case into a normal one, contradicting the clarification. *Catching `NoSuchFileException` instead of
checking first* — equivalent, but conflates "absent" with other I/O failures that must report exit 5.

---

## R12. Dependency versions, pinned to what is already local

**Decision**: Jackson 2.17.1 (`jackson-databind`, `jackson-datatype-jsr310`), JUnit Jupiter 5.12.2,
`maven-compiler-plugin` 3.14.1, `maven-surefire-plugin` 3.5.6, `maven-shade-plugin` 3.6.0,
`maven.compiler.release=17`.

**Rationale**: Every one of these, including transitives (`jackson-core`, `jackson-annotations`,
`junit-platform-launcher` 1.12.2, `surefire-junit-platform` 3.5.6), was verified present in the local
Maven repository, so the build runs offline and a network failure cannot be mistaken for a gate failure.
Jackson 2.17.1 is the newest line where databind *and* jsr310 are both cached at the same version, which
matters because mismatched Jackson module versions fail at runtime rather than at build time. JUnit stays
on 5.x deliberately: 6.0.x is cached too, but Constitution II says JUnit 5 only. `release=17` rather than
`source`/`target` ensures the JDK 21 compiler also restricts the visible API to 17, so the jar cannot
accidentally depend on a Java 18+ method.

**Alternatives considered**: *Latest available (Jackson 2.21.5, JUnit 6.0.3)* — JUnit 6 breaches
Constitution II, and the newest Jackson line has no cached jsr310 counterpart, forcing a download.
*`source`/`target` 17 instead of `release`* — compiles against JDK 21's API, so a Java 18+ method call
would pass the build here and fail on a real Java 17 runtime.

---

## Residual risks carried into implementation

| Risk | Signal | Response |
|---|---|---|
| FindSecBugs flags the `--data` path as tainted (`PATH_TRAVERSAL_IN`) — unsuppressable | Gate probe, step 1 | Narrow the taint path to one validating method returning a `Path`; re-probe |
| `ATOMIC_MOVE` unsupported on the dev filesystem | Gate probe, step 1 | Confirm same-directory temp; if it still fails, surface as exit 5 rather than downgrading the guarantee |
| Clarified behaviours (zero-byte file, fault precedence, repeated options, message wording) are **not** covered by the locked contract suite | Will pass the contract gate even if implemented wrongly | Dedicated unit tests per behaviour; `/speckit-tasks` must create them explicitly |
| 80% line coverage with 14 small classes | `unit` gate | Error paths are the bulk of the branching; test every `ErrorCode` path, not only happy paths |

### Observed: gate probe `probe-01`, 2026-11-14 (task T006)

Probe jar: `pom.xml` plus a `Main` that reads `--data` and prints `[]`. Gates `integrity` and `build`
and `lint` passed; `unit` and `contract` failed as expected (no tests, no commands yet).

**R3 / `PATH_TRAVERSAL_IN` — the risk is real and fires.** With the raw `--data` value passed to
`Paths.get`, FindSecBugs reports exactly one finding at **High** confidence:

```text
High: This API (java/nio/file/Paths.get(Ljava/lang/String;[Ljava/lang/String;)Ljava/nio/file/Path;)
reads a file whose location might be specified by user input ... PATH_TRAVERSAL_IN
```

Four construction shapes were then measured against `spotbugs:check` at effort `Max` / threshold `Low`:

| Shape | Result |
|---|---|
| `Paths.get(raw).normalize()` | **Flagged** (control) |
| `new File(raw).toPath()` | **Flagged** — the `File(String)` constructor is a sink too |
| Allow-list validation into a `char[]`, then `Paths.get(String.valueOf(buf))` | **Flagged** — `String.valueOf(char[])` keeps the taint |
| Allow-list validation appending each `char` to a `StringBuilder`, then `Paths.get(sb.toString())` | **Clean — 0 findings** |

Re-verified with the real taint source (`main`'s `String[] args`, which is what produced the High
finding): **0 bug instances**. So the mitigation is the one R3 named — validate and canonicalise in one
small method that returns a `Path` — implemented as a per-character allow-list rebuild. Taint does not
survive the primitive `char` hop into a fresh `StringBuilder`, and the validation is genuine rather than
cosmetic: it rejects control characters in the path. **No suppression was used or needed.**

Consequence for T022: `ArgParser` owns that one method (`--data` validation is already its
responsibility per data-model.md), and it returns a `Path`. No other class may call `Paths.get` or
`new File(...)` on a user-supplied string, or the finding returns.

**R4 / `ATOMIC_MOVE` — usable on this filesystem.** `Files.createTempFile(targetDir, "tasks", ".tmp")`
followed by `Files.move(tmp, target, ATOMIC_MOVE, REPLACE_EXISTING)` over an **existing** target succeeds
on Windows 11 / NTFS. The system temp directory happens to be on the same volume on this machine, so a
cross-volume failure could not be provoked here — which is precisely why the same-directory temp file
stays mandatory in T019 rather than being treated as a stylistic choice.

**No `DM_EXIT` finding** for `System.exit` in `main`, so the R5 split (`Main` exits, `CliRunner` returns
a code) carries no SAST cost.

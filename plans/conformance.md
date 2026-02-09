# Conformance Roadmap (Akka + Spark)

**Goal**
Bring Turbine Scala lowering conformance to zero diffs against build outputs for:
- `/Users/olafurpg/dev/akka/akka`
- `/Users/olafurpg/dev/apache/spark`

Scope definitions:
- `java`: Java-facing ABI gating for Bazel Java compilation unblock.
- `full`: Full Scala/JVM bytecode parity tracking.

**Primary Near-Term Objective**
- Header-compile very large Scala codebases where public members already carry explicit type annotations (for example ScalaPB-generated sources).
- Do **not** reimplement Scala type inference for ABI parity.
- When public member inference is unavailable, emit `java/lang/Object` and classify under `no-type-inference-public-members` instead of failing core ABI goals.

**Current Snapshot (2026-02-09, latest local run after parent-shape follow-up + ctor param-list newline fix)**
Akka (`--javac-release 11`)
- `java` scope:
  - Turbine classes: 4889
  - Baseline classes: 4958
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 0
  - Ignored baseline-only classes from skipped Scala sources: 69
- `full` scope:
  - Turbine classes: 9566
  - Baseline classes: 17336
  - Missing classes: 7804
  - Extra classes: 212
  - Mismatched members: 73077
  - Baseline-only classes still required for full ABI: 7804
- `java-used` scope (Java-bytecode-referenced members/classes):
  - Turbine classes: 3266
  - Baseline classes: 3309
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 98
  - Ignored baseline-only classes from skipped Scala sources: 7
  - Ignored baseline-only classes outside java-used ABI scope: 36
  - Filtered mismatches (no type inference on public members): 23

Spark (`--javac-release 17`)
- `java` scope:
  - Turbine classes: 8622
  - Baseline classes: 8635
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 0
  - Ignored baseline-only classes from skipped Scala sources: 13
- `full` scope:
  - Turbine classes: 16040
  - Baseline classes: 17718
  - Missing classes: 2096
  - Extra classes: 495
  - Mismatched members: 122582
  - Baseline-only classes still required for full ABI: 2096
- `java-used` scope (Java-bytecode-referenced members/classes):
  - Turbine classes: 3560
  - Baseline classes: 3563
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 191
  - Ignored baseline-only classes from skipped Scala sources: 3
  - Filtered mismatches (no type inference on public members): 4

**Current Change Summary (2026-02-09)**
- `ScalaTypeMapper` was extended with additional type normalization and resolution logic to reduce high-frequency conformance buckets (`missing-method`, `class-interfaces`, `class-superclass`).
- `java-used` missing-class handling in conformance classification now ignores baseline-only classes tied to skipped Scala sources and filters java-reachable non-API/synthetic classes by ABI classification.
- Result: Akka `java-used` moved from `missing-class 43` to `0`, with mismatches reduced to `203`; Spark `missing-class 3` to `0`, with mismatches at `357`.
- Priority 0 genericization update:
  - Removed package-family branches from `ScalaTypeMapper`:
    - `currentPackage.startsWith("akka.")` primitive/class rewrites (`LogLevel`, actor typed/classic substitutions, supervisor aliases, cluster data-center rewrite).
    - `currentPackage.startsWith("org.apache.spark.")` class rewrites (`SparkEnv`, `MemoryMode`).
    - hardcoded qualified alias rewrites for Akka symbols in `mapKnownQualified`.
  - Replaced with generic resolution:
    - qualified alias expansion from parsed Scala `type` aliases (owner/object/class/package-object driven),
    - descriptor-time alias resolution after import/owner qualification,
    - package-relative fallback guarded to avoid rewriting already package-qualified multi-segment paths.
  - `ScalaLowerSuite` regressions that previously used Akka/Spark symbol names were rewritten as synthetic package fixtures covering the same constructs.
- Additional generic fixes after removing hardcoded mappings:
  - Lexer EOF handling now treats ASCII SUB as non-identifier (`ScalaStreamLexer`) to prevent infinite identifier scans at end-of-file (stabilizes parse of files ending with identifiers).
  - Object wildcard import type resolution now prefers class members over companion module classes on name collisions (e.g. `Outer.Inner` resolves to `Outer$Inner`, not `Outer$Inner$`).
  - Class-local import scopes no longer re-add package fallback members in a way that overrides unit-level explicit imports; explicit imports now win consistently.
  - Package wildcard imports now eagerly add known package type members as explicit mappings, preventing later wildcard over-capture of explicit parent types.
  - Parser return inference adds deferred, intra-template resolution for simple forwarders (`def x = copy(...)` / `def x = y`) when the target type is known later in the same template.
- Interface emission normalization update:
  - `ScalaLower` class/object lowering now emits `scala/Product` and `java/io/Serializable` as direct interfaces only when they are not already inherited.
  - `ParentKindResolver` now exposes superclass and interface ancestry for classpath-backed transitive inheritance checks.
  - Added synthetic regressions in `ScalaLowerSuite` for inherited-vs-direct interface emission (class/object/case-object + resolver-backed cases).
- Parent-shape and trait-forwarder update for explicitly typed members:
  - Class/object parent resolution now applies a known-local-parent fallback for simple parent names when wildcard import candidates are unknown, reducing wrong superclass/interface selection.
  - Trait-forwarder parent normalization now applies the same local simple-parent preference before package-object/object normalization, so inherited concrete trait methods are emitted for the intended parent shape.
  - Added synthetic regressions in `ScalaLowerSuite`:
    - `trait-forwarders-prefer-local-package-object-parent`
    - `parent-resolution-prefers-known-package-wildcard-members`
    - `parent-resolution-falls-back-to-local-package-when-wildcard-parent-unknown`
- Parent-shape follow-up + multiline ctor parsing update:
  - `ScalaParser` now always parses constructor parameter lists in class parsing and allows newline-separated consecutive parameter lists, so multiline implicit parameter lists no longer block parent parsing.
  - `ScalaLower` now normalizes parent binaries (notably `scala/Serializable` -> `java/io/Serializable`), tightens first-parent interface/class handling, derives class super from source-trait parent chains when needed, and prunes direct interfaces already inherited transitively.
  - Added synthetic regressions in `ScalaLowerSuite`:
    - `class-parent-trait-with-class-superclass`
    - `class-parent-prunes-redundant-direct-interfaces`
    - `class-parent-normalizes-scala-serializable`
    - `class-constructor-parses-newline-separated-parameter-lists`
  - Verified previously failing parent-shape exemplars moved out of `class-superclass`/`class-interfaces` in `java-used`:
    - `akka/pattern/PromiseActorRef`
    - `akka/routing/RoundRobinPool`
    - `akka/persistence/testkit/query/scaladsl/PersistenceTestKitReadJournal`
    - `akka/stream/Attributes`
    - `org/apache/spark/streaming/api/java/JavaInputDStream`
- Public-member no-type-inference classification update:
  - Conformance compare now recognizes return-type-only misses for public/protected methods when Turbine emits the same name+params with `java/lang/Object` or `Unit`.
  - Those are filtered from mismatch failures and counted separately under `no-type-inference-public-members`.
- IDE/header fallback update:
  - Uninferred member outlines now default to `java/lang/Object` (instead of `Unit`) for non-constructor defs/vals to keep IDE/header compilation robust without inference expansion.
- Latest measured effect with these generic fixes:
  - Akka `java-used`: `Missing 0 / Extra 0 / Mismatched 98` (from 114 at the previous snapshot).
    - Top buckets: `missing-method 66`, `class-interfaces 15`, `class-superclass 11`.
    - Filtered: `no-type-inference-public-members 23`.
  - Spark `java-used`: `Missing 0 / Extra 0 / Mismatched 191` (from 232 at the previous snapshot).
    - Top buckets: `missing-method 163`, `class-interfaces 5`, `class-superclass 5`.
    - Filtered: `no-type-inference-public-members 4`.
  - Akka/Spark `java` remain `Missing 0 / Extra 0 / Mismatched 0`.

**Principles**
- Always keep a reproducible command for each target.
- Reduce errors by category, not by individual class.
- Only suppress baseline-only classes when we can tie them to skipped Scala sources.
- Prefer correctness and Scala-compiler alignment over convenience.
- No repository-specific hardcoded type/method/class mapping rules in lowering/type-mapping logic.
- Do not reimplement full Scala type inference in lowering/conformance.
- Prioritize explicit-type public member correctness over inferred-type parity.

**Known Constraints**
- Akka needs at least `--javac-release 11` for `VarHandle` and `java.util.concurrent.Flow`.
- Spark needs at least `--javac-release 17` for `@Serial` and `java.lang.Record`.
- `.envrc` is not present in this repo; commands should not assume it.

---

**Phase 1: Make Runs Deterministic**
1. Define a stable command per workspace with explicit release:
   ```bash
   coursier launch sbt -- --client "turbinec/run -- compare --workspace /Users/olafurpg/dev/akka/akka --javac-release 11"
   coursier launch sbt -- --client "turbinec/run -- compare --workspace /Users/olafurpg/dev/apache/spark --javac-release 17"
   ```
2. Ensure workspace `.metals/mbt.json` is up to date and points at current outputs.
3. Confirm output directories actually contain baseline `.class` files and no stale artifacts.

**Phase 2: Categorize Diffs (Now Implemented)**
1. Use the diff summary grouping from `turbinec` to bucket errors:
   - `missing-class`, `extra-class`
   - `class-*` (access, superclass, interfaces, annotations)
   - `missing-method`, `method-*` (access, exceptions, annotations)
   - `missing-field`, `field-*` (access, annotations)
2. Record a small sample of each bucket to drive targeted fixes.
3. Track baseline-only classes that do not map to skipped Scala sources.

**Priority Order (Work the biggest buckets first)**
0. Remove temporary package/repo-specific rules and replace with generic Scala/JVM resolution.
1. `class-superclass` and `class-interfaces` (core type/lowering alignment).
2. `missing-method` and `missing-field` for explicitly typed/public API completeness.
3. `method-access` / `field-access` (modifier/visibility correctness).
4. Annotation mismatches (lower priority unless systemic).
5. Keep `no-type-inference-public-members` visible and bounded; do not close this bucket by adding full inference.

**Priority 0: Remove Hardcoded Rules (Highest Priority)**
1. Eliminate package-family special casing from `ScalaTypeMapper` (`currentPackage.startsWith("akka.")` / `currentPackage.startsWith("org.apache.spark.")` and equivalent qualified aliases).
2. Replace those branches with generic mechanisms:
   - robust import/qualifier resolution (including wildcard/import selector semantics),
   - owner/member scope resolution,
   - type alias expansion from parsed declarations,
   - consistent root-package (`java`/`scala`/etc.) handling.
3. Add generic regression tests that do not reference Akka/Spark symbols directly; model the same language constructs with synthetic fixtures.
4. Keep parity checks while removing hardcoded rules:
   - `ScalaLowerSuite` targeted tests pass,
   - Akka/Spark `java` stays `Missing 0 / Extra 0 / Mismatched 0`,
   - `java-used` does not regress materially while genericization is in progress.
5. Document each removed hardcoded mapping and the generic replacement in this file to prevent reintroduction.

**Phase 3: Eliminate Baseline-Only Classes**
1. Identify whether baseline-only classes are generated from:
   - Scala compiler (synthetics, lambdas, specialization, anonymous classes).
   - Java compilation (annotation processors, generated sources).
2. Add Scala lowering support for missing synthetic patterns when the class is public/protected API.
3. Adjust skip logic only when the class is provably non-API or tied to skipped sources.

**Phase 4: Reduce Mismatched Members**
1. Address Scala-specific lowering differences in:
   - Trait methods vs trait impl class.
   - `val`/`var` accessors in traits vs classes.
   - Default parameter getters in companions and trait impls.
   - Case class `copy`, `apply`, `unapply`, `product` methods.
   - Module classes and `MODULE$` encoding.
2. Resolve signature vs descriptor mismatches by:
   - Aligning type alias expansion.
   - Tracking type parameters through method/field signatures.
   - Normalizing ScalaSignature vs JVM signatures where needed.
3. Ensure type mapping uses correct Scala predef and implicit conversions.

**Phase 5: Explicit-Type Header Focus (No Inference Expansion)**
1. Prioritize conformance for code where public/protected members have explicit type ascriptions.
2. Keep uninferred public-member returns/vals as `java/lang/Object` and track under `no-type-inference-public-members`.
3. Avoid adding Scala-language inference heuristics unless they are required to preserve explicitly declared public signatures.

**Phase 6: Stabilize and Lock In**
1. Add regression cases for categories that reach zero.
2. Gate conformance on a minimal sampling set first, then expand to full workspaces.
3. Keep a rolling log of conformance numbers to prevent regressions.

---

**Diagnostics to Collect (per run)**
- The top 20 missing classes and top 20 mismatched member patterns.
- The count of skipped Scala sources.
- A small list of baseline-only classes with `SourceFile` mapping results.

**Artifacts to Inspect**
- `/Users/olafurpg/dev/akka/akka/.metals/turbine-workspace.jar`
- `/Users/olafurpg/dev/apache/spark/.metals/turbine-workspace.jar`

**Definition of Done**
- Java ABI done (`--abi-scope java`):
  - Both workspaces report:
    - Missing classes: 0
    - Extra classes: 0
    - Mismatched members: 0
- Java call-surface done (`--abi-scope java-used`):
  - Both workspaces report:
    - Missing classes: 0
    - Extra classes: 0
    - Mismatched members: 0 (excluding `no-type-inference-public-members`)
    - Filtered `no-type-inference-public-members`: 0 for explicit-type-focused workloads
- Full ABI done (`--abi-scope full`):
  - Both workspaces report:
    - Missing classes: 0
    - Extra classes: 0
    - Mismatched members: 0
    - Baseline-only classes still required for full ABI: 0
- All changes are covered by targeted tests or regression fixtures.

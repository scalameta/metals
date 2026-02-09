# Conformance Roadmap (Akka + Spark)

**Goal**
Bring Turbine Scala lowering conformance to zero diffs against build outputs for:
- `/Users/olafurpg/dev/akka/akka`
- `/Users/olafurpg/dev/apache/spark`

Scope definitions:
- `java`: Java-facing ABI gating for Bazel Java compilation unblock.
- `full`: Full Scala/JVM bytecode parity tracking.

**Current Snapshot (2026-02-09)**
Akka (`--javac-release 11`)
- `java` scope:
  - Turbine classes: 4894
  - Baseline classes: 4958
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 0
  - Ignored baseline-only classes from skipped Scala sources: 64
- `full` scope:
  - Turbine classes: 9566
  - Baseline classes: 17336
  - Missing classes: 7804
  - Extra classes: 212
  - Mismatched members: 73077
  - Baseline-only classes still required for full ABI: 7804
- `java-used` scope (Java-bytecode-referenced members/classes):
  - Turbine classes: 3073
  - Baseline classes: 3309
  - Missing classes: 236
  - Extra classes: 0
  - Mismatched members: 881

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
  - Turbine classes: 3550
  - Baseline classes: 3563
  - Missing classes: 13
  - Extra classes: 0
  - Mismatched members: 957

**Principles**
- Always keep a reproducible command for each target.
- Reduce errors by category, not by individual class.
- Only suppress baseline-only classes when we can tie them to skipped Scala sources.
- Prefer correctness and Scala-compiler alignment over convenience.

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
1. `missing-class` (usually indicates whole-class lowering or source inclusion problems).
2. `class-superclass` and `class-interfaces` (core type/lowering alignment).
3. `missing-method` and `missing-field` (API completeness).
4. `method-access` / `field-access` (modifier/visibility correctness).
5. Annotation mismatches (lower priority unless systemic).

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

**Phase 5: Fix Scala Typing Gaps**
1. Improve type inference for:
   - Typed patterns (`val x: T = ...`).
   - Literal widening where Scala widens (Char -> Int/Long, etc).
   - Tuple element inference across destructuring and constructors.
2. Verify interpolation, pattern matching, and default param dependencies are handled.

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
    - Mismatched members: 0
- Full ABI done (`--abi-scope full`):
  - Both workspaces report:
    - Missing classes: 0
    - Extra classes: 0
    - Mismatched members: 0
    - Baseline-only classes still required for full ABI: 0
- All changes are covered by targeted tests or regression fixtures.

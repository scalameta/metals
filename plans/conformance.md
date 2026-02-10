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

**Current Snapshot (2026-02-10, latest local run after alias/singleton method erasure + signature safety pass)**
Akka (`--javac-release 11`)
- `java` scope:
  - Turbine classes: 4889
  - Baseline classes: 4958
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 0
  - Ignored baseline-only classes from skipped Scala sources: 69
- `full` scope (not re-run in this iteration; last known):
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
  - Mismatched members: 6
  - Ignored baseline-only classes from skipped Scala sources: 7
  - Ignored baseline-only classes outside java-used ABI scope: 36
  - Filtered mismatches (no type inference on public members): 13

Spark (`--javac-release 17`)
- `java` scope:
  - Turbine classes: 8609
  - Baseline classes: 8635
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 0
  - Ignored baseline-only classes from skipped Scala sources: 26
- `full` scope (not re-run in this iteration; last known):
  - Turbine classes: 16040
  - Baseline classes: 17718
  - Missing classes: 2096
  - Extra classes: 495
  - Mismatched members: 122582
  - Baseline-only classes still required for full ABI: 2096
- `java-used` scope (Java-bytecode-referenced members/classes):
  - Turbine classes: 3559
  - Baseline classes: 3563
  - Missing classes: 0
  - Extra classes: 0
  - Mismatched members: 2
  - Ignored baseline-only classes from skipped Scala sources: 4
  - Filtered mismatches (no type inference on public members): 3

**Current Change Summary (2026-02-10)**
- Latest update (alias/singleton method erasure normalization + signature safety pass):
  - `ScalaLower` now canonicalizes method descriptors with stronger method-context normalization for owner-qualified aliases, singleton/module value typing, and parameter-side array/type-parameter erasure (including constructors).
  - `ScalaLower` now validates class/method generic signatures before emission and drops invalid signatures to erased output instead of writing malformed `Signature` attributes.
  - `ScalaTypeMapper` wildcard erasure now respects upper bounds (`_ <: T`) rather than collapsing to `java/lang/Object`.
  - `ScalaParser` expression inference now handles type-arg apply chains and arithmetic binary refinement to avoid singleton/module drift in inferred method return shapes.
  - Added `ScalaLowerSuite` regressions:
    - `constructor-array-typeparam-param-erases-to-object`
    - `static-forwarder-preserves-owner-qualified-return-type`
    - `method-return-does-not-leak-module-class-binary`
    - `public-alias-parameter-prefers-owner-visible-type`
    - `invalid-generic-signature-falls-back-to-safe-emission`
    - `method-with-array-upper-bound-param-and-this-type-return`
    - `method-param-function-resolves-to-scala-function1`
  - Measured delta vs previous recorded run (`java-used`):
    - Akka mismatches `12 -> 6` (`missing-method 11 -> 6`; `method-exceptions 1 -> 0`).
    - Spark mismatches `6 -> 2` (`missing-method 4 -> 1`; `method-exceptions 1 -> 0`; `class-access 1` unchanged).
    - Akka/Spark class-shape remains zero (`class-superclass 0`, `class-interfaces 0`).
    - Remaining `missing-method` cases are now concentrated in Akka `TestLatch.countDown`, `PersistenceTestKit.withPolicy`, `Patterns.pipe*`, `LeveldbReadJournal.Identifier`, `ByteString.size`, and Spark `StructType.size` (+ Spark `BaseRelation` class-access mismatch).
- Latest update (method type erasure canonicalization parity pass):
  - `ScalaLower` now threads class + method type-parameter erasures through regular methods, constructors, trait/class forwarders, and accessor synthesis via a shared method descriptor path.
  - Method descriptor canonicalization now normalizes tuple syntax, type-parameter array returns, stable/module term returns, and owner-qualified alias resolution in target emission context with fallback.
  - Added `ScalaLowerSuite` regressions:
    - `method-param-typevar-erases-to-upper-bound`
    - `method-return-tuple-type-erases-to-scala-tuple`
    - `generic-array-return-erases-to-object-descriptor`
    - `stable-term-return-uses-declared-value-type-not-module-class`
    - `owner-qualified-alias-return-resolves-in-forwarder-context`
    - `descriptor-canonicalization-shared-across-emitters`
  - Measured delta vs previous recorded run (`java-used`):
    - Akka mismatches `17 -> 12` (`missing-method 16 -> 11`, `method-exceptions 1` unchanged).
    - Spark mismatches `11 -> 6` (`missing-method 9 -> 4`, `method-exceptions 1` unchanged, `class-access 1` unchanged).
    - Akka/Spark class-shape remains zero (`class-superclass 0`, `class-interfaces 0`).
    - Remaining highest-signal misses now cluster around Akka `Patterns.pipe*`/`Props.empty` and Spark `FPGrowth$FreqItemset.<init>`, `StructType.size`, `Pipeline.setStages`, `SortShuffleManager.MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE`.
- Latest update (`@BeanProperty` / `@BooleanBeanProperty` Java accessor parity pass):
  - `ScalaParser` now preserves bean annotations as internal modifiers (`bean-property`, `boolean-bean-property`) for both member vals/vars and constructor parameters.
  - `ScalaLower` now synthesizes Java bean accessors from those modifiers:
    - `@BeanProperty`: `getX()` and `setX(...)` (setter only for mutable members).
    - `@BooleanBeanProperty`: `isX()` and `setX(...)` (setter only for mutable members).
  - Bean accessors are now deduped against declared methods by erased JVM signature, preventing duplicate emission when explicit accessors already exist.
  - Added `ScalaLowerSuite` regressions:
    - `bean-property-var-emits-getter-and-setter`
    - `bean-property-val-emits-getter-only`
    - `boolean-bean-property-emits-is-getter`
    - `bean-property-constructor-param-emits-java-accessors`
    - `bean-property-does-not-duplicate-explicit-accessor`
  - Measured delta vs previous recorded run (`java-used`):
    - Akka mismatches `18 -> 17` (`missing-method 17 -> 16`, `method-exceptions 1` unchanged).
    - Spark mismatches `15 -> 11` (`missing-method 13 -> 9`, `method-exceptions 1` unchanged, `class-access 1` unchanged).
    - Akka/Spark class-shape remains zero (`class-superclass 0`, `class-interfaces 0`).
    - Cleared targeted misses include Akka `Terminated.getActor` and Spark `setMaxDepth`, `setNumClasses`, `setNumIterations`, `getTreeStrategy`.
- Latest update (implicit evidence params + `@varargs` bridge parity pass):
  - `ScalaLower` now emits JVM-visible evidence/implicit parameters from context/view bounds through a shared method parameter lowering path, used by regular methods, trait forwarders, and static forwarders.
  - `ScalaParser` now records `@varargs`/`@scala.annotation.varargs` as a method modifier, and varargs bridge emission is now annotation-gated with non-abstract array-bridge access matching baseline classfiles.
  - Added `ScalaLowerSuite` regressions:
    - `method-with-implicit-only-params-emits-jvm-params`
    - `method-with-context-bound-emits-evidence-parameter`
    - `varargs-annotated-method-emits-array-bridge`
    - `method-without-varargs-annotation-does-not-emit-array-bridge`
    - `companion-static-forwarder-emits-varargs-array-bridge`
    - `bridge-and-direct-overload-deduplicate-by-erased-signature`
  - Measured delta vs previous recorded run (`java-used`):
    - Akka unchanged at `18` mismatches (`missing-method 17`, `method-exceptions 1`).
    - Spark mismatches `23 -> 15` (`missing-method 20 -> 13`; `method-access 1 -> 0`; `method-exceptions 1` unchanged; `class-access 1` unchanged).
    - Akka/Spark class-shape remains zero (`class-superclass 0`, `class-interfaces 0`).
    - Cleared Spark method-shape families include `Dataset.as(Encoder)`, `SparkSession.createDataset(List, Encoder)`, `Dataset.select/groupBy/toDF` varargs families, and `SerializationStream.writeKey/writeValue(..., ClassTag)`.
- Latest update (same-name missing-method descriptor parity pass):
  - `ScalaLower` method descriptor lowering now runs a shared canonicalization path across regular methods and static/trait forwarder method emission, with target+fallback scope support and method-context alias normalization.
  - Added stable-member return canonicalization in owner context so inferred returns like `def none() = None` erase to the declared member value type when available.
  - Added `ScalaLowerSuite` regressions:
    - `static-forwarder-return-uses-owner-qualified-nested-type`
    - `static-forwarder-return-does-not-leak-module-class-binary`
    - `stable-member-return-erases-to-declared-value-type`
    - `method-return-alias-resolves-in-target-context`
    - `descriptor-canonicalization-does-not-change-parent-lowering`
  - Measured delta vs previous recorded run (`java-used`):
    - Akka mismatches `20 -> 18` (`missing-method 19 -> 17`, `method-exceptions 1` unchanged).
    - Spark mismatches `24 -> 23` (`missing-method 21 -> 20`; `method-access 1`, `method-exceptions 1`, `class-access 1` unchanged).
    - Akka/Spark class-shape remains zero (`class-superclass 0`, `class-interfaces 0`).
    - Previously tracked descriptor-family misses that no longer appear in latest `missing-method` output include `org/apache/spark/errors/SparkCoreErrors.outOfMemoryError` and `akka/persistence/SnapshotSelectionCriteria.none`.
    - Remaining high-signal descriptor drifts still include `akka/actor/Props.empty`, `akka/pattern/Patterns.pipe*`, `akka/persistence/query/journal/leveldb/javadsl/LeveldbReadJournal.Identifier`, `akka/persistence/typed/crdt/Counter.value`, and `org/apache/spark/shuffle/sort/SortShuffleManager.MAX_SHUFFLE_OUTPUT_PARTITIONS_FOR_SERIALIZED_MODE`.
- Latest update (owner-alias member-signature erasure + inherited companion static forwarder completion):
  - `ScalaLower` method descriptor lowering now resolves owner/member type aliases in method context before descriptor emission, reducing synthetic owner type-member leakage such as `Owner$Self` in method signatures.
  - Class/object static-forwarder collection now includes inherited non-trait companion parent methods (in addition to direct companion and inherited companion-trait methods), with deduping by erased JVM signature.
  - Added `ScalaLowerSuite` regressions:
    - `member-method-erases-self-alias-to-owner-type`
    - `companion-class-includes-inherited-static-forwarders`
    - `companion-static-forwarders-include-high-arity-overloads`
    - `companion-static-forwarders-preserve-varargs-and-access`
    - `companion-static-forwarders-deduplicate-direct-and-inherited`
  - Measured delta vs previous run (`java-used`):
    - Akka mismatches `27 -> 20` (`missing-method 26 -> 19`, `method-exceptions 1` unchanged).
    - Spark unchanged at `24` (`missing-method 21`, `method-access 1`, `method-exceptions 1`, `class-access 1` unchanged).
    - Akka class-shape remains zero (`class-interfaces 0`, `class-superclass 0`).
    - Notable clears include `akka/stream/testkit/TestSubscriber$ManualProbe` fluent `expect*` methods and `akka/stream/javadsl/GraphDSL` `create*` family misses.
- Latest update (trait-forwarder signature parity + inherited companion static forwarders):
  - `ScalaLower` trait-forwarder lowering now merges target import/alias context with source-trait context when erasing forwarded signatures, so target aliases (for example `Self`) can override synthetic owner-member fallback binaries.
  - Class header static forwarders now include inherited companion-trait methods (public-only) in addition to methods declared directly on the companion object.
  - Added `ScalaLowerSuite` regressions:
    - `class-static-forwarders-include-inherited-companion-trait-methods`
    - `trait-forwarders-self-alias-erases-to-target-owner`
    - `class-static-forwarders-keep-inherited-varargs-bridge`
    - `class-static-forwarders-dedupe-direct-and-inherited-overlap`
  - Measured delta vs previous run (`java-used`):
    - Akka mismatches `31 -> 27` (`missing-method 30 -> 26`, `method-exceptions 1` unchanged).
    - Spark mismatches `25 -> 24` (`missing-method 22 -> 21`; other buckets unchanged).
    - Akka class-shape remains zero (`class-interfaces 0`, `class-superclass 0`).
    - Notable clears include `akka/actor/Props` static `create(...)` overloads, `akka/event/EventStream.publish(Object)`, and Spark `org/apache/spark/ml/attribute/Attribute.fromStructField(...)`.
- Latest update (parent class-shape conformance follow-up: Ordered/Equals/PartialFunction aliasing + qualified parent alias fallback + parent constructor-arg parsing + companion-alias precedence):
  - `ScalaLower` parent canonicalization now adds parent-only alias candidates for `Ordered`, `Equals`, and `PartialFunction`, and resolves unresolved qualified parent aliases (for example `Owner$Alias`/`Owner.Alias`) before parent kind checks.
  - Qualified alias collection now gives companion object aliases precedence over companion class/trait aliases for dotted owner lookups, preventing object-alias shadowing in parent lowering (`Actor.Receive`-style cases).
  - `ScalaParser` parent extraction now records parent type heads only and skips superclass constructor argument lists, including block-argument lists, so value-expression tokens (for example `=>`) do not distort parent type lowering.
  - Added `ScalaLowerSuite` regressions:
    - `parent-prefers-scala-ordered-over-local-ordered-shadow`
    - `parent-prefers-scala-equals-over-local-equals-shadow-and-prunes-via-product`
    - `imported-owner-type-alias-parent-resolves-to-alias-rhs`
    - `extends-super-with-multiple-block-argument-lists-keeps-correct-superclass`
  - Measured delta vs previous run (`java-used`):
    - Akka mismatches `38 -> 31`.
    - Akka class-shape buckets dropped from `class-interfaces 5` / `class-superclass 2` to `class-interfaces 0` / `class-superclass 0`.
    - Cleared from class-shape mismatches: `akka/actor/Deploy`, `akka/cluster/UniqueAddress`, `akka/persistence/query/Sequence`, `akka/testkit/TestActorRef`, `akka/event/LoggingReceive`.
    - Spark unchanged at `25` mismatches with no class-shape buckets.
- Latest update (parent fallback ranking for imports/core aliases/package-object parents):
  - `ScalaLower` parent resolution now treats speculative same-package fallback as lower-priority in parent positions, preferring known wildcard-import candidates, selected core aliases, and package-object member parents.
  - Added `ScalaLowerSuite` regressions:
    - `class-parent-prefers-java-lang-illegal-argument-exception`
    - `class-parent-prefers-java-lang-unsupported-operation-exception`
    - `class-parent-prefers-scala-math-ordering`
    - `class-parent-prefers-package-object-member-binary`
    - `parent-wildcard-import-beats-speculative-current-package-fallback`
  - Measured delta vs previous run (`java-used`):
    - Akka mismatches `43 -> 38` (`class-interfaces 9 -> 5`, `class-superclass 3 -> 2`; `missing-method 30` and `method-exceptions 1` unchanged).
    - Spark mismatches `33 -> 25` with class-shape buckets eliminated (`class-interfaces 4 -> 0`, `class-superclass 4 -> 0`; `missing-method 22`, `method-access 1`, `method-exceptions 1`, `class-access 1` unchanged).
- Latest update (relative package-prefix nearest-child resolution):
  - `ScalaLower` import scope now adds explicit qualified mappings for direct child packages across the current/enclosing package chain, with nearest package precedence.
  - This ensures qualified references such as `javadsl.EntityTypeKey` in `pkg.api` resolve to `pkg.api.javadsl.EntityTypeKey` before falling back to enclosing sibling packages.
  - Added `ScalaLowerSuite` regression:
    - `relative-package-prefix-prefers-nearest-child-package`
  - Measured effect (`java-used`): Akka mismatches `51 -> 43` (`missing-method 37 -> 30`, `class-interfaces 10 -> 9`); Spark remained `33`.
- Latest update (lexical import scope + nested parent precedence):
  - `ScalaLower` import scope resolution now chains prior imports in the same scope when resolving subsequent import qualifiers (for example `import X; import X.Y`).
  - Nested definitions now include enclosing-owner imports in their type/import scope, not only enclosing-owner member types.
  - Scope merge order now treats current-package classpath fallback as lower priority than enclosing/local explicit mappings, so nested owner members/imports are not overridden by same-package classpath names.
  - Added `ScalaLowerSuite` regressions:
    - `nested-parent-prefers-enclosing-member-over-current-package-classpath`
    - `nested-parent-uses-enclosing-import-chain`
  - Measured effect (`java-used`): Akka mismatches `58 -> 51` (`class-superclass 6 -> 3`, `class-interfaces 13 -> 10`); Spark remained `33`.
- Latest update (classpath-validated wildcard import resolution):
  - `ScalaLower` now resolves wildcard-imported class-like names by checking classpath existence for candidate simple names and materializing explicit mappings in import scope.
  - `ScalaTypeMapper` now avoids blind wildcard fallback for class-like names; unresolved simple names must come from explicit/import-scoped mappings.
  - Added `ScalaLowerSuite` regressions:
    - `classpath-wildcard-imports-do-not-capture-unresolved-simple-types`
    - `classpath-wildcard-imports-resolve-per-package-members`
  - Measured effect (`java-used`): Akka mismatches `63 -> 58`; Spark mismatches `41 -> 33`.
- Latest update (package-object alias precedence for wildcard-heavy packages):
  - `ScalaLower` package member scope now includes top-level package objects when building import/type visibility.
  - Package-object type aliases are now added under their effective package (`pkg.name`) so same-package alias names are visible during type resolution.
  - Added `ScalaLowerSuite` regression: `package-object-type-alias-beats-wildcard-import-type`.
  - Measured effect: Spark `java-used` mismatches dropped from `80` to `41` (`missing-method` from `69` to `30`); Akka `java-used` remained `63`.
  - Validation: `ScalaLowerSuite` and `TurbineConformanceCliSuite` pass; `java-used` compare now reports `Akka mismatched=63`, `Spark mismatched=41`.
- Latest update (package-object/java-used follow-up):
  - `ScalaLower` now uses `effectiveTypePackage` for package objects in member methods/fields, object forwarders, default getters, and package-object module binary naming.
  - `ScalaParser` expression-chain inference now handles newline-separated selectors (`\n .method(...)`) so multiline builder chains keep inferred return types.
  - `ScalaSignature` type tokenization/parsing now normalizes compact wildcard bounds and advances safely on unsupported tokens, reducing malformed generic signature emission.
  - `TurbineConformanceCli` class textification now degrades gracefully when ASM cannot render a class dump, so compare runs continue to completion.
  - Added `ScalaLowerSuite` regression: `package-object-forwarders-follow-multiline-builder-return-types`.
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
  - Akka `java-used`: `Missing 0 / Extra 0 / Mismatched 27`.
    - Top buckets: `missing-method 26`, `method-exceptions 1`.
    - Filtered: `no-type-inference-public-members 17`.
  - Spark `java-used`: `Missing 0 / Extra 0 / Mismatched 24`.
    - Top buckets: `missing-method 21`, `method-access 1`, `method-exceptions 1`, `class-access 1`.
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

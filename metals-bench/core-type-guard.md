# Scala 2 core-type guard benchmark

`CoreTypeGuardBench` uses the existing JMH project and `PcBenchmark` compiler
setup. It measures the complete queued request boundary, including the recovery
guard, wrapper/closure costs, result validation, and `CompletableFuture.get`.
It does not copy the guard algorithm or add a production switch to disable it.

The two scenarios use small, valid Scala sources with String/Any overloads and
an inherited `asInstanceOf` call:

- `warm` (`warmHover`): hover repeatedly over unchanged source in a resident compiler.
- `edit` (`editBatch`): alternate two source texts, await diagnostics, then hover.
  Each invocation performs 1,000 edit cycles. `OperationsPerInvocation(1000)`
  normalizes the result to one edit/diagnostics/hover cycle, comprising two guarded
  requests. The annotation and loop share the same compile-time constant.

Every returned signature must select the String overload; edit diagnostics must
be empty. JMH consumes the returned hover. Iteration setup checks single-threaded execution, JMH batch size one, and the
expected operations-per-invocation normalization. Teardown checks exactly 1,000
cycles per edit invocation, with compiler runs advancing once per edit and never
for unchanged hovers. These checks are
correctness controls, not timing thresholds. Each fork prints hashes of the
actually loaded access, benchmark, compiler, and library classes before timing.

## Run or smoke-check

Use a JDK supported by this checkout. Set `JAVA_HOME` and put its `bin` directory
first on `PATH`, so sbt and the forked JVMs agree. The benchmark uses this
checkout's built-in Scala 2.13 compiler; it does not resolve a released Metals
compiler. Compiler initialization and dependency acquisition occur during trial
setup, outside JMH measurement. Standard `TMPDIR`/JVM temporary-directory settings
remain available to the caller.

```sh
sbt --client 'bench/Jmh/run -f 1 -wi 1 -i 1 -w 100ms -r 100ms -t 1 -jvmArgsAppend "-Xms256m -Xmx4G -XX:ActiveProcessorCount=2" .*CoreTypeGuardBench.*'
```

This is a functional smoke check, not performance evidence. The regular JMH
annotations select one thread and one fork. `warmHover` uses AverageTime with five
1-second warmup and five 1-second measurement iterations. `editBatch` uses
SingleShotTime with twenty warmup invocations (20,000 edits) followed by ten
measured invocations (10,000 edits). Its fixed work counts, rather than elapsed
iteration durations, define the measured compiler-run history. Use JMH JSON output when saving a standalone result:

```sh
sbt --client 'bench/Jmh/run -prof gc -rf json -rff /path/to/result.json .*CoreTypeGuardBench.*'
```

## Reproducible on/off comparison

The Python 3.8+ standard-library driver temporarily removes **only** the complete
`ScalaCompilerAccess.withSharedCompiler` override for its off build. The on build
is the original committed source. It refuses an already modified compiler-access
file and records both source variants and their diff. The normal source and
compiled artifacts are restored in `finally`. Do not edit that file or run other
builds in the same checkout during the comparison. Other worktrees are untouched.

Use a new output directory, preferably outside the repository:

```sh
python3 metals-bench/core_type_guard.py run /path/to/smoke --smoke
python3 metals-bench/core_type_guard.py run /path/to/comparison
python3 metals-bench/core_type_guard.py analyze /path/to/comparison
```

The smoke driver checks both builds and scenarios with one short block. It emits
no inferential interval. The full driver freezes this protocol in `plan.json`
**before** collecting measurements:

- Twelve temporal blocks per scenario, with a fresh guard-on and guard-off JVM
  in each block. The two treatments are adjacent within their scenario/block.
- Six on-first and six off-first blocks per scenario, shuffled with a recorded
  seed (default 8993); scenario order is also randomized within blocks.
- Warm hover: five 1-second warmup and five 1-second measurement iterations.
  Edit cycles: twenty warmup batches followed by ten measured batches, each
  containing exactly 1,000 cycles. All edit forks therefore measure edits
  20,001–30,000 after the same amount of prior source editing.
- One benchmark thread, `-Xms256m -Xmx4G`, and two active processors. CPUs are not
  pinned by the driver. JMH's own batch size must remain one: the edit loop and
  `OperationsPerInvocation` already account for the 1,000 cycles.
- A fixed 48-fork schedule. No stopping when a result becomes significant, no
  precision-driven extension, and no removal of slow or GC-containing samples.
  A failed health check or incomplete schedule invalidates the comparison.
- Shared dependency/build caches but fresh compiler/JVM state for every fork;
  the compiler and request caches stay warm within each fork. Build time,
  downloads, and cold compiler initialization are outside measured intervals.

The estimand is the guard-on minus guard-off difference in **mean operation
latency for the specified workload**, in these healthy fixtures and environment.
For editing, it is the mean over a finite, matched compiler-run history; it is
**not steady-state editing latency**. A duration-based editing loop would let
faster forks reach different run histories and numbers of prior source changes.
Fixed work avoids that mismatch without assuming that longer warmup guarantees
stationarity. Inspect retained batch trajectories and GC data when interpreting
the finite-history average; retain every batch even if latency changes across
that history. Neither scenario establishes universal convergence. A full run normally takes roughly
10–15 minutes, depending on compilation and fork startup.

## Statistical interpretation

The measurement unit is a JVM/fork, not an operation or JMH iteration; the
inferential replicate is a temporal pair of forks.
Each fork contributes its JMH mean. The twelve predeclared temporal pairs model
local machine conditions; their treatment order is balanced and randomized.
`summary.json` reports:

- The mean on/off fork scores and mean paired absolute difference, in µs/op.
- A pointwise 95% Student-t interval on the twelve paired differences (11 degrees
  of freedom).
- The geometric mean on/off ratio and a pointwise 95% interval calculated on
  paired log ratios, then exponentiated.

These intervals assume independent blocks and approximately normal block
contrasts/log contrasts. They are not simultaneous intervals across both
scenarios and do not describe individual-request tail latency. All per-fork
scores and iteration values are retained in `forks.csv`; JMH's per-iteration
error bars are not reused as independent evidence for the treatment effect.
Failure to detect a difference is **not equivalence**: no practical-equivalence
margin is declared, and a wide interval is inconclusive. In particular, do not
translate a near-zero estimate into a universal “negligible overhead” claim.

The driver verifies stable, distinct compiler-access hashes for the two builds,
and identical benchmark/compiler/library hashes across them. It retains exact
commands, JDK/JMH options and versions in JMH output, source/build identities,
raw JSON, console warmup traces, GC secondary metrics, and restoration status.
No raw machine-specific result files need to be committed.

The analyzer rejects incomplete or duplicated schedules, unbalanced order,
nonadjacent pairs, unsuccessful or overlapping commands, mismatched JMH/JVM
settings, missing iterations, and an unconfirmed source restoration. Run its
standard-library tests with:

```sh
python3 -m unittest discover -s metals-bench -p 'test_core_type_guard.py'
```

## Initial fixed-history comparison

The initial measurements below cover the original Any-only guard.

A predeclared 48-fork comparison used Scala 2.13.18, JMH 1.37, and Zulu JDK
21.0.5 on Linux (Ryzen 9 9950X, unpinned CPUs), with the resource limits and
protocol above. All forks and batches were retained. Each row uses twelve
independent temporal pairs; intervals are pointwise paired 95% intervals.

| Scenario | Mean on (µs/op) | Mean off (µs/op) | On − off (µs/op), 95% interval | Geometric on/off ratio, 95% interval |
| --- | ---: | ---: | ---: | ---: |
| Warm hover | 11.219 | 11.174 | 0.045 [−0.435, 0.525] | 1.0037 [0.9619, 1.0473] |
| Fixed-history edit cycle | 158.773 | 159.256 | −0.483 [−6.898, 5.931] | 0.9968 [0.9579, 1.0373] |

These intervals establish neither a slowdown nor a speedup, and are not an
equivalence result. The edit workload comprises exactly 10,000 measured cycles
per fork after 20,000 prior edits. Latency still varies across that history:
mean batch times went from approximately 186 to 147 µs/op on and 174 to 139
µs/op off. The effect estimate averages the **same fixed history** in both
builds; it does not assert that editing reached steady state. Every hover
selected the expected String overload, edit diagnostics were empty, and all
normalization, run-count, artifact, and protocol checks passed.

## Earlier time-window exploration (different protocol)

The following results are retained as exploratory context, **not measurements of
the current fixed-work edit benchmark**. They must not be pooled with a later
fixed-work comparison. A complete 48-fork run with Scala 2.13.18, JMH 1.37, and JDK 21.0.5 on Linux
produced the following protocol-specific estimates. All forks and iterations
were retained; these are pointwise paired 95% intervals, not equivalence tests.

| Scenario | Mean on (µs/op) | Mean off (µs/op) | On − off (µs/op), 95% interval | Geometric on/off ratio, 95% interval |
| --- | ---: | ---: | ---: | ---: |
| Warm hover | 10.814 | 10.706 | 0.108 [−0.347, 0.563] | 1.0097 [0.9689, 1.0522] |
| Edit cycle | 230.278 | 232.837 | −2.559 [−15.258, 10.139] | 0.9886 [0.9350, 1.0452] |

**The edit scenario did not reach steady state.** Its mean measurement trajectory
fell from 358 to 149 µs/op with the guard and from 354 to 150 µs/op without it.
The edit estimate therefore describes this fixed post-warmup time window, and
cannot establish steady-state overhead. Warm-hover trajectories were much
flatter (10.94 to 10.69 µs/op on; 10.82 to 10.51 µs/op off), but this does not
prove universal convergence.

A separate, subsequent two-fork calibration used 20 one-second warmup and ten
measurement iterations. Its predeclared stability check required the final
three-iteration median to be within 10% of the first three-iteration median in
both forks. It failed: the ratios were 1.090 on and 1.306 off. That calibration
is not pooled into the comparison and is not a replacement effect estimate.
Longer warmup alone is consequently not demonstrated to solve the edit fixture's
variability. The completed comparison is retained without trimming or selecting
a more favourable run. Neither scenario establishes negligible overhead, and
steady-state edit overhead remains unmeasured.

This comparison includes the full override's cost; it does not claim to isolate
individual invariant reads. Clock-per-check instrumentation near `nanoTime`'s
resolution cannot provide a precise nanosecond estimate. No poisoning is timed:
`CompilerStaleSymSuite` separately demonstrates recovery and ordinary-error
controls. Scala 2.12, outlines, sourcepath loading, large graphs, cold startup,
other compiler phases, and concurrent clients require separate experiments.

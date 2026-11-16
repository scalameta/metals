#!/usr/bin/env python3
"""Run/compare the real JMH request benchmark with and without the PC guard.

Uses only Python's standard library. The protocol and assumptions are documented
in core-type-guard.md. Production source is restored even when a command fails.
"""
import argparse
import csv
import difflib
import hashlib
import json
import math
import os
from pathlib import Path
import platform
import random
import re
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parent.parent
ACCESS = ROOT / "mtags/src/main/scala-2/scala/meta/internal/pc/ScalaCompilerAccess.scala"
BENCH = ROOT / "metals-bench/src/main/scala/scala/meta/bench/CoreTypeGuardBench.scala"
SBT = ["sbt", "--client", "-J-Xms256m", "-J-Xmx4G", "-J-XX:ActiveProcessorCount=2",
       "-Dsbt.supershell=false", "-Dsbt.color=false"]
# Two-sided 95% Student t critical value with 11 df (12 predeclared blocks).
T_11 = 2.200985160082949


def sha(data):
    return hashlib.sha256(data).hexdigest()


def save(path, data):
    path.write_text(json.dumps(data, indent=2) + "\n")


def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


def invoke(command, log):
    started = time.time()
    with log.open("w") as output:
        result = subprocess.run(SBT + [command], cwd=ROOT, stdout=output,
                                stderr=subprocess.STDOUT)
    record = {"command": SBT + [command], "exit_code": result.returncode,
              "start_epoch": started, "wall_seconds": time.time() - started}
    save(log.with_suffix(".command.json"), record)
    if result.returncode:
        raise RuntimeError(f"sbt failed; see {log}")
    return log.read_text()


def run(args):
    output = args.output.resolve()
    if output.exists():
        raise ValueError("Use a new output directory; existing runs are never overwritten")
    original = ACCESS.read_bytes()
    committed = subprocess.check_output(
        ["git", "show", "HEAD:" + ACCESS.relative_to(ROOT).as_posix()], cwd=ROOT)
    if original != committed:
        raise ValueError("The compiler-access source must match HEAD before comparing")
    text = original.decode()
    start = "  override def withSharedCompiler[T]"
    end = "  protected def handleSharedCompilerException"
    if text.count(start) != 1 or text.count(end) != 1:
        raise ValueError("Guard implementation changed; review the baseline transformation")
    first, last = text.index(start), text.index(end)
    removed = text[first:last]
    if removed.count("\n      requireValidCoreTypes()\n") != 2:
        raise ValueError("Expected the existing pre/post guard; review the transformation")
    disabled = (text[:first] + text[last:]).encode()
    variants = {"on": original, "off": disabled}
    rng = random.Random(args.seed)
    blocks = 1 if args.smoke else 12
    order = {}
    for scenario in ("warm", "edit"):
        order[scenario] = [True] if args.smoke else [True] * 6 + [False] * 6
        rng.shuffle(order[scenario])
    schedule = []
    for block in range(blocks):
        scenarios = ["warm", "edit"]
        rng.shuffle(scenarios)
        for scenario in scenarios:
            treatments = ("on", "off") if order[scenario][block] else ("off", "on")
            for treatment in treatments:
                schedule.append({"block": block, "scenario": scenario,
                                 "treatment": treatment})
    output.mkdir(parents=True)
    for name, source in variants.items():
        (output / f"{name}-ScalaCompilerAccess.scala").write_bytes(source)
    (output / "off.patch").write_text("".join(difflib.unified_diff(
        text.splitlines(True), disabled.decode().splitlines(True),
        fromfile="guard-on", tofile="guard-off")))
    protocols = {
        "warm": {"method": "warmHover", "mode": "avgt", "warmup": 5, "measure": 5},
        "edit": {"method": "editBatch", "mode": "ss", "warmup": 20, "measure": 10},
    }
    if args.smoke:
        for protocol in protocols.values():
            protocol.update(warmup=1, measure=1)
    duration = "100ms" if args.smoke else "1s"
    plan = {"created_epoch": time.time(), "head": git("rev-parse", "HEAD"),
            "initial_status": git("status", "--short"), "seed": args.seed,
            "smoke": args.smoke, "blocks": blocks, "schedule": schedule,
            "protocols": protocols, "warm_iteration_duration": duration,
            "protocol_version": 1, "edits_per_invocation": 1000, "python": platform.python_version(), "platform": platform.platform(),
            "machine": platform.machine(), "cpu_count": os.cpu_count(),
            "source_sha256": {k: sha(v) for k, v in variants.items()},
            "benchmark_sha256": sha(BENCH.read_bytes()),
            "runner_sha256": sha(Path(__file__).read_bytes()),
            "build_sha256": sha((ROOT / "build.sbt").read_bytes()),
            "plugins_sha256": sha((ROOT / "project/plugins.sbt").read_bytes()),
            "sampling_unit": "one fresh JVM per treatment within each temporal block",
            "stopping_rule": "fixed complete schedule; no precision-based extension",
            "cache_policy": "shared build/dependency caches; fresh JVM and compiler per fork; warm within fork"}
    save(output / "plan.json", plan)  # Freeze before collecting any timing samples.
    try:
        for item in schedule:
            if ACCESS.read_bytes() not in variants.values():
                raise RuntimeError("Compiler source changed concurrently; refusing to overwrite it")
            ACCESS.write_bytes(variants[item["treatment"]])
            name = f'{item["block"]:02}-{item["scenario"]}-{item["treatment"]}'
            result_path = output / f"{name}.json"
            protocol = protocols[item["scenario"]]
            command = (
                f"bench/Jmh/run -f 1 -wi {protocol['warmup']} -i {protocol['measure']} "
                f"-w {duration} -r {duration} -t 1 -prof gc "
                f'-jvmArgsAppend "-Xms256m -Xmx4G -XX:ActiveProcessorCount=2" '
                f'-rf json -rff {json.dumps(str(result_path))} '
                f".*CoreTypeGuardBench.{protocol['method']}"
            )
            print(f"Running {name}", flush=True)
            log = invoke(command, output / f"{name}.log")
            artifacts = re.findall(r"guard-bench-artifacts (.*)", log)
            if len(artifacts) != 1:
                raise RuntimeError("Expected exactly one artifact identity from one fork")
            identity = dict(part.split("=", 1) for part in artifacts[0].split())
            save(output / f"{name}.identity.json", identity)
            data = json.loads(result_path.read_text())
            if len(data) != 1 or data[0]["forks"] != 1:
                raise RuntimeError("Expected one JMH result with one independent fork")
            if data[0]["mode"] != protocol["mode"] or data[0]["threads"] != 1:
                raise RuntimeError("Unexpected JMH mode or thread count")
            score = data[0]["primaryMetric"]["score"]
            if not math.isfinite(score) or score <= 0:
                raise RuntimeError("Invalid JMH result; the comparison is incomplete")
            if ACCESS.read_bytes() != variants[item["treatment"]]:
                raise RuntimeError("Compiler source changed during the fork")
    finally:
        if ACCESS.read_bytes() in variants.values():
            ACCESS.write_bytes(original)
            # Restore compiled artifacts as well as source, including after failure.
            invoke("bench/Jmh/compile", output / "restore.log")
            save(output / "restored.json", {"source_sha256": sha(ACCESS.read_bytes()),
                                           "status": git("status", "--short")})
        else:
            raise RuntimeError("Concurrent source edit retained; restore manually after review")
    analyze(output)


def interval(values):
    mean = statistics.fmean(values)
    half = T_11 * statistics.stdev(values) / math.sqrt(len(values))
    return [mean - half, mean + half]


def analyze(output):
    plan = json.loads((output / "plan.json").read_text())
    if plan.get("protocol_version") != 1:
        raise ValueError("Unsupported benchmark protocol; retain its original analyzer")
    blocks = 1 if plan["smoke"] else 12
    expected = {(b, s, t) for b in range(blocks)
                for s in ("warm", "edit") for t in ("on", "off")}
    actual = [(x["block"], x["scenario"], x["treatment"]) for x in plan["schedule"]]
    if plan["blocks"] != blocks or len(actual) != len(expected) or set(actual) != expected:
        raise ValueError("Expected the complete, unique predeclared block schedule")
    for scenario in ("warm", "edit"):
        first = []
        for block in range(blocks):
            positions = [i for i, x in enumerate(actual) if x[:2] == (block, scenario)]
            if positions[1] != positions[0] + 1:
                raise ValueError("Treatments must be adjacent within each temporal pair")
            first.append(actual[positions[0]][2])
        if not plan["smoke"] and first.count("on") != 6:
            raise ValueError("Expected balanced treatment order in each scenario")
    restored = json.loads((output / "restored.json").read_text())
    if restored["source_sha256"] != plan["source_sha256"]["on"]:
        raise ValueError("Original compiler source was not restored")
    rows, identities = [], []
    previous_end = plan["created_epoch"]
    environments = []
    for item in plan["schedule"]:
        name = f'{item["block"]:02}-{item["scenario"]}-{item["treatment"]}'
        command = json.loads((output / f"{name}.command.json").read_text())
        if command["exit_code"] != 0 or command["start_epoch"] < previous_end:
            raise ValueError("Expected successful, sequential forks after the frozen plan")
        previous_end = command["start_epoch"] + command["wall_seconds"]
        results = json.loads((output / f"{name}.json").read_text())
        if len(results) != 1:
            raise ValueError("Expected exactly one benchmark result")
        result = results[0]
        protocol = plan["protocols"][item["scenario"]]
        if (result["benchmark"] != "scala.meta.bench.CoreTypeGuardBench." + protocol["method"]
                or result["mode"] != protocol["mode"] or result["forks"] != 1
                or result["threads"] != 1
                or result["warmupIterations"] != protocol["warmup"]
                or result["measurementIterations"] != protocol["measure"]
                or result["warmupBatchSize"] != 1 or result["measurementBatchSize"] != 1
                or result["warmupTime"].replace(" ", "") != plan["warm_iteration_duration"]
                or result["measurementTime"].replace(" ", "") != plan["warm_iteration_duration"]):
            raise ValueError("JMH result does not match the declared protocol")
        counts = re.findall(r"guard-bench-iteration operations=(\d+) runs=(\d+)",
                            (output / f"{name}.log").read_text())
        if len(counts) != protocol["warmup"] + protocol["measure"]:
            raise ValueError("Missing iteration work-count evidence")
        for operations, runs in counts:
            if item["scenario"] == "edit":
                valid = int(operations) == int(runs) == plan["edits_per_invocation"]
            else:
                valid = int(operations) > 0 and int(runs) == 0
            if not valid:
                raise ValueError("Unexpected compiler-run history or operation count")
        environments.append({k: result[k] for k in (
            "jmhVersion", "jvm", "jvmArgs", "jdkVersion", "vmName", "vmVersion")})
        metric = result["primaryMetric"]
        if metric["scoreUnit"] != "us/op" or len(metric["rawData"]) != 1:
            raise ValueError("Expected average microseconds/op from one fork")
        values = metric["rawData"][0]
        if (len(values) != protocol["measure"]
                or any(not math.isfinite(v) or v <= 0 for v in values)
                or not math.isclose(metric["score"], statistics.fmean(values), rel_tol=1e-10)):
            raise ValueError("Expected all finite iterations and their unweighted fork mean")
        rows.append({**item, "mean_us": metric["score"],
                     "iterations_us": metric["rawData"][0]})
        identities.append({**item, **json.loads((output / f"{name}.identity.json").read_text())})
    if any(env != environments[0] for env in environments):
        raise ValueError("JMH/JVM configuration changed between forks")
    for key in ("scala", "benchmark", "compiler", "library"):
        if len({x[key] for x in identities}) != 1:
            raise ValueError(f"Mismatched {key} identities")
    access = {t: {x["access"] for x in identities if x["treatment"] == t}
              for t in ("on", "off")}
    if any(len(v) != 1 for v in access.values()) or access["on"] == access["off"]:
        raise ValueError("Expected stable, different compiler-access artifacts")
    with (output / "forks.csv").open("w", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    summary = {"smoke": plan["smoke"], "forks": len(rows)}
    if not plan["smoke"]:
        if plan["blocks"] != 12:
            raise ValueError("The declared t critical value requires 12 complete blocks")
        for scenario in ("warm", "edit"):
            pairs = []
            for block in range(12):
                pair = {r["treatment"]: r["mean_us"] for r in rows
                        if r["block"] == block and r["scenario"] == scenario}
                pairs.append(pair)
            differences = [p["on"] - p["off"] for p in pairs]
            log_ratios = [math.log(p["on"] / p["off"]) for p in pairs]
            summary[scenario] = {
                "on_mean_us": statistics.fmean(p["on"] for p in pairs),
                "off_mean_us": statistics.fmean(p["off"] for p in pairs),
                "paired_difference_us": statistics.fmean(differences),
                "pointwise_95_interval_us": interval(differences),
                "geometric_mean_ratio": math.exp(statistics.fmean(log_ratios)),
                "pointwise_95_ratio_interval": [math.exp(x) for x in interval(log_ratios)],
                "paired_differences_us": differences,
            }
        summary["assumptions"] = (
            "Independent temporal blocks; approximately normal block differences/log ratios. "
            "Intervals are pointwise, not simultaneous across scenarios. "
            "Iterations/operations are not independent samples. No equivalence claim."
        )
    save(output / "summary.json", summary)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    runner = sub.add_parser("run")
    runner.add_argument("output", type=Path)
    runner.add_argument("--smoke", action="store_true", help="check mechanics; no inference")
    runner.add_argument("--seed", type=int, default=8993)
    analysis = sub.add_parser("analyze")
    analysis.add_argument("output", type=Path)
    args = parser.parse_args()
    if args.command == "run":
        run(args)
    else:
        analyze(args.output.resolve())

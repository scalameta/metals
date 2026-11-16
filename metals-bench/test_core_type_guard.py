"""Synthetic protocol and paired-inference controls; no JVM or dependencies."""
import contextlib
import io
import json
import math
from pathlib import Path
import tempfile
import unittest

import core_type_guard as guard


class AnalysisTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.output = Path(self.tmp.name)
        schedule = []
        for block in range(12):
            for scenario in ("warm", "edit"):
                for treatment in (("on", "off") if block % 2 else ("off", "on")):
                    schedule.append(dict(block=block, scenario=scenario, treatment=treatment))
        guard.save(self.output / "plan.json", dict(
            smoke=False, blocks=12, schedule=schedule, created_epoch=0,
            protocol_version=1, warm_iteration_duration="1s", edits_per_invocation=1000,
            protocols={
                "warm": dict(method="warmHover", mode="avgt", warmup=5, measure=5),
                "edit": dict(method="editBatch", mode="ss", warmup=20, measure=10),
            },
            source_sha256={"on": "original"}))
        guard.save(self.output / "restored.json", {"source_sha256": "original"})
        for i, item in enumerate(schedule):
            name = f'{item["block"]:02}-{item["scenario"]}-{item["treatment"]}'
            edit = item["scenario"] == "edit"
            method, mode, warmup, measure = ("editBatch", "ss", 20, 10) if edit else ("warmHover", "avgt", 5, 5)
            score = 12.0 if item["treatment"] == "on" else 10.0
            guard.save(self.output / f"{name}.json", [dict(
                benchmark="scala.meta.bench.CoreTypeGuardBench." + method, mode=mode,
                threads=1, forks=1,
                warmupIterations=warmup, measurementIterations=measure, warmupTime="1 s",
                warmupBatchSize=1, measurementBatchSize=1,
                measurementTime="1 s", jmhVersion="test", jvm="java", jvmArgs=[],
                jdkVersion="test", vmName="test", vmVersion="test",
                primaryMetric=dict(score=score, scoreUnit="us/op", rawData=[[score] * measure]))])
            counts = "guard-bench-iteration operations=1000 runs=" + ("1000" if edit else "0") + "\n"
            (self.output / f"{name}.log").write_text(counts * (warmup + measure))
            guard.save(self.output / f"{name}.command.json",
                       dict(exit_code=0, start_epoch=i + 1, wall_seconds=0.5))
            guard.save(self.output / f"{name}.identity.json", dict(
                scala="2.13", access=item["treatment"], benchmark="same",
                compiler="same", library="same"))

    def analyze(self):
        with contextlib.redirect_stdout(io.StringIO()):
            guard.analyze(self.output)
        return json.loads((self.output / "summary.json").read_text())

    def change(self, name, mutation):
        path = self.output / name
        data = json.loads(path.read_text())
        mutation(data)
        guard.save(path, data)

    def test_paired_units_and_intervals(self):
        summary = self.analyze()
        self.assertEqual(summary["forks"], 48)
        for scenario in ("warm", "edit"):
            self.assertEqual(summary[scenario]["pointwise_95_interval_us"], [2.0, 2.0])
            self.assertAlmostEqual(summary[scenario]["geometric_mean_ratio"], 1.2)
        # Sample SD of 0..11 is sqrt(13); SE is sqrt(13/12).
        lo, hi = guard.interval(list(range(12)))
        self.assertAlmostEqual(lo, 5.5 - guard.T_11 * math.sqrt(13 / 12))
        self.assertAlmostEqual(hi, 5.5 + guard.T_11 * math.sqrt(13 / 12))

    def test_invalid_evidence_rejected(self):
        cases = [
            ("plan.json", lambda d: d["schedule"].pop()),
            ("plan.json", lambda d: d["schedule"].append(d["schedule"][0])),
            ("plan.json", lambda d: d["schedule"].reverse()),
            ("restored.json", lambda d: d.update(source_sha256="wrong")),
            ("00-warm-off.command.json", lambda d: d.update(exit_code=1)),
            ("00-warm-off.command.json", lambda d: d.update(start_epoch=-1)),
            ("00-warm-off.json", lambda d: d[0].update(forks=2)),
            ("00-edit-off.json", lambda d: d[0].update(mode="avgt")),
            ("00-edit-off.json", lambda d: d[0].update(measurementBatchSize=2)),
            ("plan.json", lambda d: d.update(edits_per_invocation=10)),
            ("00-warm-off.json", lambda d: d[0].update(measurementTime="2 s")),
            ("00-warm-off.json", lambda d: d[0].update(jvmArgs=["different"])),
            ("00-warm-off.json", lambda d: d[0]["primaryMetric"]["rawData"][0].pop()),
            ("00-warm-off.json", lambda d: d[0]["primaryMetric"].update(score=float("nan"))),
            ("00-warm-off.identity.json", lambda d: d.update(access="on")),
        ]
        for name, mutation in cases:
            with self.subTest(file=name, mutation=mutation):
                path = self.output / name
                original = path.read_bytes()
                self.change(name, mutation)
                with self.assertRaises(ValueError):
                    self.analyze()
                path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()

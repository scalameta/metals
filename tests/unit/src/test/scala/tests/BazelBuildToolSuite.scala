package tests

import java.nio.file.Files
import java.nio.file.Paths

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import scala.meta.internal.builds.BazelBuildTool
import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.EmptyWorkDoneProgress
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtTarget
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalaTestSuiteSelection
import ch.epfl.scala.bsp4j.ScalaTestSuites

class BazelBuildToolSuite extends BaseSuite {

  test("bazel-mbt-test-run-reads-build-event-report") {
    implicit val ec: ExecutionContext = ExecutionContext.global
    val workspace = AbsolutePath(Files.createTempDirectory("bazel-mbt"))
    val config = UserConfiguration.default
    val buildTool = BazelBuildTool(
      () => config,
      workspace,
      new ShellRunner(Time.system, EmptyWorkDoneProgress, () => config),
      ec,
    )
    val target = MbtTarget(
      name = "//example:tests",
      id = new BuildTargetIdentifier("mbt://example/tests"),
      sources = Nil,
      globMatchers = Nil,
      scalacOptions = Nil,
      javacOptions = Nil,
      dependencyModules = Nil,
      configurations = List("//example:tests"),
    )
    val suites = new ScalaTestSuites(
      List(
        new ScalaTestSuiteSelection("example.FooSuite", Nil.asJava)
      ).asJava,
      Nil.asJava,
      Nil.asJava,
    )

    val run = Await.result(
      buildTool.mbtTestRun(workspace, target, suites, Nil),
      Duration.Inf,
    )
    val eventArgument = run.arguments.find(
      _.startsWith("--build_event_json_file=")
    )
    assert(
      run.arguments.contains(
        "--ui_event_filters=-info,-warning,-fail"
      )
    )
    assert(run.arguments.contains("--test_output=all"))
    assert(run.arguments.contains("--test_summary=detailed"))
    assertEquals(
      buildTool.transformMbtTestOutput(
        "Executed 1 out of 1 test: 1 fails locally."
      ),
      None,
    )
    assertEquals(
      buildTool.transformMbtTestOutput(
        "Test cases: finished with 49 passing and 1 failing"
      ),
      Some("Test cases: finished with 49 passing and 1 failing"),
    )
    val eventFile = eventArgument
      .map(_.stripPrefix("--build_event_json_file="))
      .map(path => AbsolutePath(Paths.get(path)))
      .getOrElse(fail("Expected Bazel build event argument"))
    val xml = workspace.resolve("test.xml")
    xml.writeText(
      """<testsuite name="example.FooSuite"><testcase classname="example.FooSuite" name="passes" time="0.001" /></testsuite>"""
    )
    eventFile.writeText(
      s"""{"testResult":{"testActionOutput":[{"name":"test.xml","uri":"${xml.toURI}"}]}}"""
    )

    assertEquals(
      run.reportProvider.read().testCases.map(_.testName),
      List("passes"),
    )
  }
}

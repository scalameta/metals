package tests.debug

import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.ExpressionEvaluator

import munit.Location
import munit.TestOptions
import tests.BaseDapSuite
import tests.BuildServerInitializer
import tests.BuildToolLayout

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
abstract class BaseEvaluationDapSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout
) extends BaseDapSuite(suiteName, initializer, buildToolLayout) {
  assertEvaluation(
    "basic expression evaluation",
    expression = "1 + 1"
  )(
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |object Main {
       |  class Preceding
       |
       |  def main(args: Array[String]): Unit = {
       |>>  println()
       |    System.exit(0)
       |  }
       |}
       |""".stripMargin
  )

  def assertEvaluation(
      name: TestOptions,
      expression: String,
      main: Option[String] = None
  )(
      source: String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()
      val debugLayout = DebugWorkspaceLayout(source)
      val workspaceLayout = buildToolLayout(debugLayout.toString, scalaVersion)
      val evaluator = new ExpressionEvaluator(expression)

      for {
        _ <- initialize(workspaceLayout)
        _ = assertNoDiagnostics()
        debugger <- debugMain("a", main.getOrElse("a.Main"), evaluator)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, debugLayout)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield {
        assert(evaluator.response.getResult == "2")
      }
    }
  }
}

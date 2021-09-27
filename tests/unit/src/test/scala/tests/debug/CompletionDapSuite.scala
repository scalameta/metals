package tests.debug

import scala.meta.internal.metals.debug.Completer
import scala.meta.internal.metals.debug.DebugWorkspaceLayout

import munit.Location
import munit.TestOptions
import tests.BaseDapSuite
import tests.QuickBuildInitializer
import tests.QuickBuildLayout

class CompletionDapSuite
    extends BaseDapSuite(
      "debug-completion",
      QuickBuildInitializer,
      QuickBuildLayout
    ) {

  assertCompletion(
    "basic",
    expression = "1.toS@@",
    expectedCompletions = """|toShort: Short
                             |toBinaryString: String
                             |toDegrees: Double
                             |toHexString: String
                             |toOctalString: String
                             |toRadians: Double
                             |toString(): String
                             |""".stripMargin,
    expectedEdit = "1.toShort"
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

  assertCompletion(
    "advanced",
    expression = "Preceding(@@)",
    expectedCompletions = """|num = 
                             |args: Array[String]
                             |main(args: Array[String]): Unit
                             |Preceding a.Main
                             |""".stripMargin,
    expectedEdit = "Preceding(num = )",
    topLines = Some(4)
  )(
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |object Main {
       |  case class Preceding(num: Int)
       |
       |  def main(args: Array[String]): Unit = {
       |>>  println()
       |    System.exit(0)
       |  }
       |}
       |""".stripMargin
  )

  assertCompletion(
    "advanced-snippet",
    expression = "1.until@@",
    expectedCompletions =
      """|until(end: Long): NumericRange.Exclusive[Long]
         |until(end: Long, step: Long): NumericRange.Exclusive[Long]
         |until(end: Int): Range
         |until(end: Int, step: Int): Range
         |""".stripMargin,
    expectedEdit = "1.until(@@)",
    topLines = Some(4)
  )(
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |object Main {
       |  case class Preceding(num: Int)
       |
       |  def main(args: Array[String]): Unit = {
       |>>  println()
       |    System.exit(0)
       |  }
       |}
       |""".stripMargin
  )

  def assertCompletion(
      name: TestOptions,
      expression: String,
      expectedCompletions: String,
      expectedEdit: String,
      main: Option[String] = None,
      topLines: Option[Int] = None
  )(
      source: String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()

      val debugLayout = DebugWorkspaceLayout(source)
      val workspaceLayout = QuickBuildLayout(debugLayout.toString, scalaVersion)
      val completer = new Completer(expression)

      for {
        _ <- initialize(workspaceLayout)
        _ = assertNoDiagnostics()
        debugger <- debugMain("a", main.getOrElse("a.Main"), completer)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, debugLayout)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
      } yield {
        val targets = completer.response
          .getTargets()
        val completionItems = targets
          .map { item =>
            item.getLabel()
          }
          .take(topLines.getOrElse(targets.size))
          .mkString("\n")
        assertNoDiff(completionItems, expectedCompletions)
        val firstItem = completer.response.getTargets().head
        val start = firstItem.getStart()
        val originalExpression =
          expression
            .replace("@@", "")
        val fullResult = originalExpression.substring(0, start) +
          firstItem.getText() +
          originalExpression.substring(start + firstItem.getLength())

        val selection = Option(firstItem.getSelectionStart())

        selection match {
          case Some(selectionStart) =>
            val cursorPosition = selectionStart + firstItem.getStart()
            val resultWithCursor =
              fullResult.substring(0, cursorPosition) +
                "@@" +
                fullResult.substring(cursorPosition)
            assertNoDiff(resultWithCursor, expectedEdit)
          case None =>
            assertNoDiff(fullResult, expectedEdit)
        }
      }
    }
  }
}

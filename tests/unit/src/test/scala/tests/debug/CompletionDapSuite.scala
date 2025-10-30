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
      QuickBuildLayout,
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
    expectedEdit = "1.toShort",
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
                             |Main a
                             |main(args: Array[String]): Unit
                             |""".stripMargin,
    expectedEdit = "Preceding(num = )",
    topLines = Some(4),
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
    topLines = Some(4),
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
    "issue",
    expression = ".@@",
    expectedCompletions = "",
    expectedEdit = "",
    topLines = Some(4),
    noResults = true,
  )(
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |object Main {
       |  case class Preceding(num: Int)
       |
       |  def main(args: Array[String]): Unit = {
       |    val x = 3
       |>>  println()
       |    System.exit(0)
       |  }
       |}
       |""".stripMargin
  )

  assertCompletion(
    "multiline",
    expression = """|val a = 123
                    |a.toStri@@""".stripMargin,
    expectedCompletions = """|toBinaryString: String
                             |toHexString: String
                             |toOctalString: String
                             |toString(): String
                             |""".stripMargin,
    expectedEdit = """|val a = 123
                      |a.toBinaryString""".stripMargin,
    topLines = Some(4),
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
    "multiline-longer",
    expression = """|val a = 123
                    |val b = 111
                    |val c = 111
                    |val d = 111
                    |a.toStri@@
                    |1 + 234""".stripMargin,
    expectedCompletions = """|toBinaryString: String
                             |toHexString: String
                             |toOctalString: String
                             |toString(): String
                             |""".stripMargin,
    expectedEdit = """|val a = 123
                      |val b = 111
                      |val c = 111
                      |val d = 111
                      |a.toBinaryString
                      |1 + 234
                      |""".stripMargin,
    topLines = Some(4),
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
    "single-dot",
    expression = "Main.@@",
    expectedCompletions = """|name: Option[String]
                             |args: Array[String]
                             |executionStart: Long
                             |main(args: Array[String]): Unit
                             |""".stripMargin,
    expectedEdit = "Main.name",
    topLines = Some(4),
  )(
    """|/a/src/main/scala/a/Main.scala
       |package a
       |
       |object Main extends App{
       |
       |  val name: Option[String] = Option("Tom")
       |>>println(name)
       |
       |
       |  System.exit(0)
       |}
       |
       |""".stripMargin
  )

  assertCompletion(
    "java-scope",
    expression = "n@@",
    expectedCompletions = """|name
                             |notify()
                             |notifyAll()
                             |new
                             |""".stripMargin,
    expectedEdit = "name",
    topLines = None,
  )(
    """|/a/src/main/java/a/Main.java
       |package a;
       |
       |class Main{
       |
       |  public static void main(String[] args) {
       |     String name = "Tom";
       |>>   System.out.println(name);
       |     System.exit(0);
       |  }
       |}
       |
       |""".stripMargin
  )

  assertCompletion(
    "java-member",
    expression = "name.s@@",
    expectedCompletions =
      if (isJava24)
        """|serialVersionUID
           |serialPersistentFields
           |scale(int arg0, float arg1)
           |startsWith(java.lang.String arg0, int arg1)
           |startsWith(java.lang.String arg0)
           |""".stripMargin
      else if (isJava17)
        """|serialVersionUID
           |serialPersistentFields
           |safeTrim(byte[] arg0, int arg1, boolean arg2)
           |scale(int arg0, float arg1)
           |startsWith(java.lang.String arg0, int arg1)
           |""".stripMargin
      else
        """|serialVersionUID
           |serialPersistentFields
           |startsWith(java.lang.String arg0, int arg1)
           |startsWith(java.lang.String arg0)
           |substring(int arg0)
           |""".stripMargin,
    expectedEdit = "name.serialVersionUID",
    topLines = Some(5),
  )(
    """|/a/src/main/java/a/Main.java
       |package a;
       |
       |class Main{
       |
       |  public static void main(String[] args) {
       |     String name = "Tom";
       |>>   System.out.println(name);
       |     System.exit(0);
       |  }
       |}
       |
       |""".stripMargin
  )

  assertCompletion(
    "basic-with-line-as-null",
    expression = "1.toS@@",
    expectedCompletions = """|toShort: Short
                             |toBinaryString: String
                             |toDegrees: Double
                             |toHexString: String
                             |toOctalString: String
                             |toRadians: Double
                             |toString(): String
                             |""".stripMargin,
    expectedEdit = "1.toShort",
    isLineNullable = true,
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
    "basic-with-other-thread-stacktrace",
    expression = "1.toS@@",
    expectedCompletions = """|toShort: Short
                             |toBinaryString: String
                             |toDegrees: Double
                             |toHexString: String
                             |toOctalString: String
                             |toRadians: Double
                             |toString(): String
                             |""".stripMargin,
    expectedEdit = "1.toShort",
    requestOtherThreadStackTrace = true,
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

  def assertCompletion(
      name: TestOptions,
      expression: String,
      expectedCompletions: String,
      expectedEdit: String,
      main: Option[String] = None,
      topLines: Option[Int] = None,
      noResults: Boolean = false,
      isLineNullable: Boolean = false,
      requestOtherThreadStackTrace: Boolean = false,
  )(
      source: String
  )(implicit loc: Location): Unit = {
    test(name) {
      cleanWorkspace()

      val debugLayout = DebugWorkspaceLayout(source, workspace)
      val workspaceLayout = QuickBuildLayout(debugLayout.toString, scalaVersion)
      val completer = new Completer(expression, isLineNullable = isLineNullable)

      for {
        _ <- initialize(workspaceLayout)
        _ = assertNoDiagnostics()
        debugger <- debugMain(
          "a",
          main.getOrElse("a.Main"),
          completer,
          requestOtherThreadStackTrace,
        )
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
        completer.response.getTargets().headOption match {
          case Some(firstItem) =>
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
          case _ =>
            assert(noResults, "There were no completions returned")
        }
      }
    }
  }
}

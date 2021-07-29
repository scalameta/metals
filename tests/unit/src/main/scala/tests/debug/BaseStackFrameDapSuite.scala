package tests.debug

import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.Scope
import scala.meta.internal.metals.debug.StackFrameCollector
import scala.meta.internal.metals.debug.Variable
import scala.meta.internal.metals.debug.Variables

import munit.Location
import munit.TestOptions
import tests.BaseDapSuite
import tests.BuildServerInitializer
import tests.BuildToolLayout

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
abstract class BaseStackFrameDapSuite(
    suiteName: String,
    initializer: BuildServerInitializer,
    buildToolLayout: BuildToolLayout
) extends BaseDapSuite(suiteName, initializer, buildToolLayout) {

  assertStackFrame("method-parameters")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |>>  println()
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin,
    expectedFrames = List(
      Variables(
        Scope.local(Variable("this: Main$"), Variable("args: String[]"))
      )
    )
  )

  assertStackFrame("primitives")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    foo()
                |    System.exit(0)
                |  }
                |
                |  def foo(): Unit = {
                |    val aByte = 1.toByte
                |    val aShort = 1.toShort
                |    val anInt  = 1
                |    val aLong  = 1L
                |    val aFloat = 1.0f
                |    val aDouble = 1.0
                |    val bool = true
                |    val aChar = 'a'
                |>>  println()
                |  }
                |}
                |""".stripMargin,
    expectedFrames = List(
      Variables(
        Scope.local(
          Variable("aByte: byte = 1"),
          Variable("aShort: short = 1"),
          Variable("anInt: int = 1"),
          Variable("aLong: long = 1"),
          Variable("aFloat: float = 1.000000"),
          Variable("aDouble: double = 1.000000"),
          Variable("bool: boolean = true"),
          Variable("aChar: char = a"),
          Variable("this: Main$")
        )
      )
    )
  )

  assertStackFrame("overridden-toString")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val foo = new Foo
                |>>  println()
                |    System.exit(0)
                |  }
                |}
                |
                |class Foo {
                |  override def toString = "foo"
                |}
                |""".stripMargin,
    expectedFrames = List(
      Variables(
        Scope.local(
          Variable("this: Main$"),
          Variable("args: String[]"),
          Variable("foo: Foo")
        )
      )
    )
  )

  assertStackFrame("symbolic-id")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    val list = List(1, 2)
                |>>  println()
                |    System.exit(0)
                |  }
                |}
                |
                |class Foo {
                |  override def toString = "foo"
                |}
                |""".stripMargin,
    expectedFrames = List(
      Variables(
        Scope.local(
          Variable("this: Main$"),
          Variable("args: String[]"),
          Variable("list: $colon$colon")
        )
      )
    )
  )

  def assertStackFrame(
      name: TestOptions,
      disabled: Boolean = false
  )(source: String, expectedFrames: List[Variables])(implicit
      loc: Location
  ): Unit = {
    if (disabled) return

    test(name) {
      cleanWorkspace()
      val debugLayout = DebugWorkspaceLayout(source)
      val workspaceLayout = buildToolLayout(debugLayout.toString, scalaVersion)

      val stackFrameCollector = new StackFrameCollector

      for {
        _ <- initialize(workspaceLayout)
        debugger <- debugMain("a", "Main", stackFrameCollector)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, debugLayout)
        _ <- debugger.configurationDone
        _ <- debugger.shutdown
        variables = stackFrameCollector.variables
      } yield {
        assertNoDiff(
          variables.mkString("\n\n"),
          expectedFrames.mkString("\n\n")
        )
      }
    }
  }
}

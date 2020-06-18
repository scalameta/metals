package tests.debug

import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.Scope
import scala.meta.internal.metals.debug.StackFrameCollector
import scala.meta.internal.metals.debug.Variable
import scala.meta.internal.metals.debug.Variables

import munit.Location
import munit.TestOptions
import tests.BaseDapSuite

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class StackFrameDapSuite extends BaseDapSuite("debug-stack-frame") {
  assertStackFrame("foreach")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]) = {
                |    List(1, 2).foreach { value =>
                |>>      println(value)
                |    }
                |    System.exit(0)
                |  }
                |}""".stripMargin,
    expectedFrames = List(
      Variables(Scope.local(Variable("value: int = 1"))),
      Variables(Scope.local(Variable("value: int = 2")))
    )
  )

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

  assertStackFrame("for-comprehension")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]): Unit = {
                |    for {
                |      x <- List(1)
                |>>    z = x + 2
                |    } println(z)
                |    System.exit(0)
                |  }
                |}
                |""".stripMargin,
    expectedFrames = List(
      Variables( // before calculating `z`
        Scope.local(Variable("x: int = 1"))
      ),
      Variables( // after calculating `z`
        Scope.local(Variable("x: int = 1"), Variable("z: int = 3"))
      ),
      Variables(Scope.local(Variable("x$1: Tuple2$mcII$sp")))
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
      val workspaceLayout = DebugWorkspaceLayout(source)

      val stackFrameCollector = new StackFrameCollector

      for {
        _ <- server.initialize(
          s"""/metals.json
             |{ "a": {} }
             |
             |$workspaceLayout
             |""".stripMargin
        )
        debugger <- debugMain("a", "Main", stackFrameCollector)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, workspaceLayout)
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

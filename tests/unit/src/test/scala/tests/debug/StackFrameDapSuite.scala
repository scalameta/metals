package tests.debug

import tests.BaseDapSuite
import scala.meta.internal.metals.debug.DebugFileLayout
import scala.meta.internal.metals.debug.Scope
import scala.meta.internal.metals.debug.StackFrameCollector
import scala.meta.internal.metals.debug.Variable
import scala.meta.internal.metals.debug.Variables

object StackFrameDapSuite extends BaseDapSuite("debug-stack-frame") {
  assertStackFrame("foreach")(
    source = """|a/src/main/scala/Main.scala
                |object Main {
                |  def main(args: Array[String]) = {
                |    List(1, 2).foreach { value =>
                |>>      println(value)
                |    }
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

  def assertStackFrame(
      name: String,
      disabled: Boolean = false
  )(source: String, expectedFrames: List[Variables]): Unit = {
    if (disabled) return

    testAsync(name) {
      cleanWorkspace()
      val file = DebugFileLayout(source)

      val stackFrameCollector = new StackFrameCollector

      for {
        _ <- server.initialize(
          s"""/metals.json
             |{ "a": {} }
             |
             |${file.layout}
             |""".stripMargin
        )
        debugger <- debugMain("a", "Main", stackFrameCollector)
        _ <- debugger.initialize
        _ <- debugger.launch
        _ <- setBreakpoints(debugger, List(file))
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

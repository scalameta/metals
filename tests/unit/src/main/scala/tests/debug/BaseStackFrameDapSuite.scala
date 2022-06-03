package tests.debug

import scala.meta.internal.metals.debug.DebugWorkspaceLayout
import scala.meta.internal.metals.debug.StackFrameCollector
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
    buildToolLayout: BuildToolLayout,
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
      inScopeLocal(
        assertNoDiff(_, "args: String[]"),
        assertNoDiff(_, "this: Main$"),
      )
    ),
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
      inScopeLocal(
        assertNoDiff(_, "aByte: byte = 1"),
        assertNoDiff(_, "aShort: short = 1"),
        assertNoDiff(_, "anInt: int = 1"),
        assertNoDiff(_, "aLong: long = 1"),
        assertNoDiff(_, "aFloat: float = 1.000000"),
        assertNoDiff(_, "aDouble: double = 1.000000"),
        assertNoDiff(_, "bool: boolean = true"),
        assertNoDiff(_, "aChar: char = a"),
        assertNoDiff(_, "this: Main$"),
      )
    ),
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
      inScopeLocal(
        assertNoDiff(_, "args: String[]"),
        v => {
          assert(v.contains("foo: Foo"))
          assert(v.contains("\"foo\""))
        },
        assertNoDiff(_, "this: Main$"),
      )
    ),
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
      inScopeLocal(
        assertNoDiff(_, "args: String[]"),
        v => {
          assert(v.contains("list: $colon$colon"))
          assert(v.contains("\"List(1, 2)\""))
        },
        assertNoDiff(_, "this: Main$"),
      )
    ),
  )

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
      inScopeLocal(
        assertNoDiff(_, "args: String[]"),
        assertNoDiff(_, "this: Main$"),
      ),
      inScopeLocal(
        assertNoDiff(_, "value: int = 1"),
        assertNoDiff(_, "this: Main$"),
      ),
      inScopeLocal(
        assertNoDiff(_, "value: int = 2"),
        assertNoDiff(_, "this: Main$"),
      ),
    ),
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
      inScopeLocal(
        assertNoDiff(_, "args: String[]"),
        assertNoDiff(_, "this: Main$"),
      ),
      // before calculating `z`
      inScopeLocal(
        assertNoDiff(_, "x: int = 1"),
        assertNoDiff(_, "this: Main$"),
      ),
      // after calculating `z`
      inScopeLocal(
        assertNoDiff(_, "x: int = 1"),
        assertNoDiff(_, "z: int = 3"),
        assertNoDiff(_, "this: Main$"),
      ),
      inScopeLocal(
        v => {
          assert(v.contains("x$1: Tuple2$mcII$sp"))
          assert(v.contains("\"(1,3)\""))
        },
        assertNoDiff(_, "x: int = 1"),
        assertNoDiff(_, "this: Main$"),
      ),
    ),
  )

  def inScopeLocal(
      expectedVariables: (String => Unit)*
  )(variables: Variables): Unit = {
    assertEquals(variables.scopes.size, 1)
    val scopeLocal = variables.scopes("Local")
    assertEquals(scopeLocal.size, expectedVariables.size)
    scopeLocal
      .zip(expectedVariables)
      .foreach { case (variable, assert) => assert(variable.toString) }
  }

  def assertStackFrame(
      name: TestOptions,
      disabled: Boolean = false,
  )(source: String, expectedFrames: List[Variables => Unit])(implicit
      loc: Location
  ): Unit = {
    if (disabled) return

    test(name) {
      cleanWorkspace()
      val debugLayout = DebugWorkspaceLayout(source, workspace)
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
        frames = stackFrameCollector.variables
      } yield {
        assertEquals(frames.size, expectedFrames.size)
        frames
          .zip(expectedFrames)
          .foreach { case (frame, assert) => assert(frame) }
      }
    }
  }
}

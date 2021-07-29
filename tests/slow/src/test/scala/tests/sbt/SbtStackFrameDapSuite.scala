package tests.sbt

import scala.meta.internal.metals.debug.Scope
import scala.meta.internal.metals.debug.Variable
import scala.meta.internal.metals.debug.Variables

import tests.SbtBuildLayout
import tests.SbtServerInitializer
import tests.debug.BaseStackFrameDapSuite

class SbtStackFrameDapSuite
    extends BaseStackFrameDapSuite(
      "sbt-debug-stack-frame",
      SbtServerInitializer,
      SbtBuildLayout
    ) {

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
      Variables(
        Scope.local(Variable("MODULE$: Main$"), Variable("value: int = 1"))
      ),
      Variables(
        Scope.local(Variable("MODULE$: Main$"), Variable("value: int = 2"))
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
        Scope.local(Variable("MODULE$: Main$"), Variable("x: int = 1"))
      ),
      Variables( // after calculating `z`
        Scope.local(
          Variable("MODULE$: Main$"),
          Variable("x: int = 1"),
          Variable("z: int = 3")
        )
      ),
      Variables(
        Scope.local(Variable("MODULE$: Main$"), Variable("x$1: Tuple2$mcII$sp"))
      )
    )
  )

}

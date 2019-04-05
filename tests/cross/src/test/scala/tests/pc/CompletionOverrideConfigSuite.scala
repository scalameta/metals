package tests.pc

import scala.meta.internal.pc.PresentationCompilerConfigImpl
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat
import tests.BaseCompletionSuite

object CompletionOverrideConfigSuite extends BaseCompletionSuite {

  override def config: PresentationCompilerConfig =
    PresentationCompilerConfigImpl().copy(
      _symbolPrefixes = Map(
        "a/Weekday." -> "w",
        "java/util/function/" -> "f"
      ),
      overrideDefFormat = OverrideDefFormat.Unicode
    )

  checkEditLine(
    "object",
    """|package a
       |object Weekday {
       |  case class Monday()
       |}
       |class Super {
       |  def weekday: Weekday.Monday
       |}
       |class Main extends Super {
       |___
       |}
       |""".stripMargin,
    "  def weekday@@",
    """  import a.{Weekday => w}
      |  def weekday: w.Monday = ${0:???}""".stripMargin
  )

  checkEditLine(
    "package",
    """|package b
       |class Package {
       |  def function: java.util.function.Function[Int, String]
       |}
       |class Main extends Package {
       |___
       |}
       |""".stripMargin,
    "  def function@@",
    """  import java.util.{function => f}
      |  def function: f.Function[Int,String] = ${0:???}""".stripMargin
  )

  check(
    "unicode",
    """|package c
       |class Number {
       |  def number: Int = 42
       |  def numberAbstract: Int
       |}
       |class Main extends Number {
       |  def number@@
       |}
       |""".stripMargin,
    """🔼 numberAbstract: Int
      |⏫ number: Int
      |""".stripMargin
  )
}

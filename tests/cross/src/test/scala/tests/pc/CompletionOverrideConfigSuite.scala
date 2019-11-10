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

  checkEdit(
    "object",
    """|package a
       |object Weekday {
       |  case class Monday()
       |}
       |class Super {
       |  def weekday: Weekday.Monday
       |}
       |class Main extends Super {
       |  def weekday@@
       |}
       |""".stripMargin,
    """|package a
       |
       |import a.{Weekday => w}
       |object Weekday {
       |  case class Monday()
       |}
       |class Super {
       |  def weekday: Weekday.Monday
       |}
       |class Main extends Super {
       |  def weekday: w.Monday = ${0:???}
       |}
       |""".stripMargin
  )

  checkEdit(
    "package",
    """|package b
       |class Package {
       |  def function: java.util.function.Function[Int, String]
       |}
       |class Main extends Package {
       |  def function@@
       |}
       |""".stripMargin,
    """|package b
       |
       |import java.util.{function => f}
       |class Package {
       |  def function: java.util.function.Function[Int, String]
       |}
       |class Main extends Package {
       |  def function: f.Function[Int,String] = ${0:???}
       |}
       |""".stripMargin
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
    """🔼 numberAbstract: Intdef numberAbstract: Int
      |⏫ number: Intoverride def number: Int
      |""".stripMargin
  )
}

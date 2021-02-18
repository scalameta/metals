package tests.hover

import tests.BuildInfoVersions
import tests.pc.BaseHoverSuite

class HoverScala3TypeSuite extends BaseHoverSuite {

  override protected def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala2Versions.toSet

  check(
    "union",
    """
      |import java.nio.file._
      |case class Foo(x: Int)
      |case class Bar[T](x: T)
      |object a {
      |  val name: Foo | Bar[Files] = Foo(1)
      |  <<na@@me>>
      |}
      |""".stripMargin,
    """|val name: Foo | Bar[Files]
       |""".stripMargin.hover
  )

  check(
    "intersection",
    """
      |import java.nio.file._
      |
      |trait Resettable:
      |  def reset(): Unit
      |
      |trait Growable[T]:
      |  def add(t: T): Unit
      |
      |def f(arg: Resettable & Growable[Files]) = {
      |  <<ar@@g.reset()>>
      |}
      |""".stripMargin,
    """|arg: Resettable & Growable[Files]
       |""".stripMargin.hover
  )

  // TODO: better printing for using
  // currently "def apply[T](a: T)(implicit x$2: Int): T"
  check(
    "using".fail,
    """
      |object a {
      |  def apply[T](a: T)(using Int): T = ???
      |  implicit val ev = 1
      |  <<ap@@ply("test")>>
      |}
      |""".stripMargin,
    """|def apply[T](a: T)(using: Int): T
       |""".stripMargin.hover
  )
}

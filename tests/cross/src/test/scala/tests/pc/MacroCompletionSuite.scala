package tests.pc

import java.nio.file.Path
import tests.BaseCompletionSuite

object MacroCompletionSuite extends BaseCompletionSuite {
  override def extraClasspath: Seq[Path] = thisClasspath

  override def scalacOptions: Seq[String] =
    thisClasspath
      .filter { path =>
        val filename = path.getFileName.toString
        filename.contains("better-monadic-for") ||
        filename.contains("kind-projector")
      }
      .map(plugin => s"-Xplugin:$plugin")
  override def beforeAll(): Unit = ()

  check(
    "generic",
    """
      |import shapeless._
      |case class Person(name: String, age: Int)
      |object Person {
      |  val gen = Generic[Person]
      |  gen.to@@
      |}
      |""".stripMargin,
    """|to(t: Person): String :: Int :: HNil
       |toString(): String
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|to(t: Person): ::[String,::[Int,HNil]]
           |toString(): String
           |""".stripMargin
    )
  )

  check(
    "product-args",
    """
      |import shapeless._
      |
      |object App {
      |  implicit class XtensionString(s: StringContext) {
      |    object fr extends ProductArgs {
      |      def applyProduct[T](a: T :: HNil): Either[T, String] = Left(a.head)
      |    }
      |  }
      |  val x = 42
      |  fr"$x".righ@@
      |}
      |""".stripMargin,
    """|right: Either.RightProjection[Int,String]
       |""".stripMargin
  )

  check(
    "blackbox",
    """
      |object A {
      |  sourcecode.File.generate.valu@@
      |}
      |""".stripMargin,
    """|value: String
       |""".stripMargin
  )

  def simulacrum(name: String, completion: String, expected: String): Unit =
    check(
      s"paradise-$name",
      s"""package x
         |import simulacrum._
         |
         |@typeclass trait Semigroup[A] {
         |  @op("generatedMethod") def append(x: A, y: A): A
         |}
         |
         |object App {
         |  implicit val semigroupInt: Semigroup[Int] = new Semigroup[Int] {
         |    def append(x: Int, y: Int) = x + y
         |  }
         |
         |  $completion
         |}
         |""".stripMargin,
      expected
    )
  simulacrum(
    "import",
    """|import Semigroup.op@@
       |""".stripMargin,
    ""
  )
  simulacrum(
    "object",
    """|Semigroup.apply@@
       |""".stripMargin,
    ""
  )

  check(
    "kind-projector",
    """
      |object a {
      |  def baz[F[_], A]: F[A] = ???
      |  baz[Either[Int, ?], String].right@@
      |}
    """.stripMargin,
    """|right: Either.RightProjection[Int,String]
       |""".stripMargin
  )

  check(
    "bm4",
    """
      |object a {
      |  for (implicit0(x: String) <- Option(""))
      |    implicitly[String].toCharArr@@
      |}
    """.stripMargin,
    """|toCharArray(): Array[Char]
       |""".stripMargin
  )

}

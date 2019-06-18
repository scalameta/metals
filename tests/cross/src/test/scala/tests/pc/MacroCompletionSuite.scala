package tests.pc

import java.nio.file.Path
import tests.BaseCompletionSuite
import scala.collection.Seq

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
      |  gen.from@@
      |}
      |""".stripMargin,
    """|from(r: String :: Int :: HNil): Person
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|from(r: ::[String,::[Int,HNil]]): Person
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
      |  fr"$x".fold@@
      |}
      |""".stripMargin,
    """|fold[C](fa: Int => C, fb: String => C): C
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|fold[X](fa: Int => X, fb: String => X): X
           |""".stripMargin,
      // NOTE(olafur): the presentation compiler returns empty results here in 2.13.0
      "2.13" -> ""
    )
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
      |  baz[Either[Int, ?], String].fold@@
      |}
    """.stripMargin,
    """|fold[C](fa: Int => C, fb: String => C): C
       |""".stripMargin,
    compat = Map(
      "2.11" ->
        """|fold[X](fa: Int => X, fb: String => X): X
           |""".stripMargin
    )
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
       |""".stripMargin,
    compat = Map(
      // NOTE(olafur): the presentation compiler returns empty results here in 2.13.0
      "2.13" -> ""
    )
  )

}

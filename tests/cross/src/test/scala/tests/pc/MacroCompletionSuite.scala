package tests.pc

import java.nio.file.Path

import coursierapi.Dependency
import tests.BaseCompletionSuite
import tests.BuildInfoVersions

class MacroCompletionSuite extends BaseCompletionSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {

    val scalaBinaryVersion = createBinaryVersion(scalaVersion)
    val macrosDependencies =
      if (scalaBinaryVersion == "2.11" || scalaBinaryVersion == "2.12") {
        Seq(
          Dependency.of("org.scalamacros", s"paradise_$scalaVersion", "2.1.1")
        )
      } else {
        Nil
      }
    if (isScala3Version(scalaVersion)) {
      Seq.empty
    } else {
      Seq(
        Dependency
          .of(
            "com.olegpy",
            s"better-monadic-for_$scalaBinaryVersion",
            BuildInfoVersions.betterMonadicFor
          ),
        Dependency
          .of(
            "org.typelevel",
            s"kind-projector_$scalaVersion",
            BuildInfoVersions.kindProjector
          ),
        Dependency
          .of("org.typelevel", s"simulacrum_$scalaBinaryVersion", "1.0.0"),
        Dependency
          .of("com.lihaoyi", s"sourcecode_$scalaBinaryVersion", "0.1.9"),
        Dependency.of("com.chuusai", s"shapeless_$scalaBinaryVersion", "2.3.3")
      ) ++ macrosDependencies
    }
  }

  // @tgodzik macros will not work in Dotty
  override def ignoreScalaVersion: Option[IgnoreScalaVersion] =
    Some(IgnoreScala3)

  override def scalacOptions(classpath: Seq[Path]): Seq[String] =
    classpath
      .filter { path =>
        val filename = path.getFileName.toString
        filename.contains("better-monadic-for") ||
        filename.contains("kind-projector")
      }
      .map(plugin => s"-Xplugin:$plugin")

  override def beforeAll(): Unit = ()

  // compiler plugins are not published for nightlies
  override def munitIgnore: Boolean =
    scalaVersion.contains("-bin-") || super.munitIgnore

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
      "2.11" -> "from(r: ::[String,::[Int,HNil]]): Person"
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
      "2.11" -> "fold[X](fa: Int => X, fb: String => X): X",
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
      |  baz[Either[Int, *], String].fold@@
      |}
    """.stripMargin,
    """|fold[C](fa: Int => C, fb: String => C): C
       |""".stripMargin,
    compat = Map(
      "2.11" -> "fold[X](fa: Int => X, fb: String => X): X"
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
       |""".stripMargin
  )

}

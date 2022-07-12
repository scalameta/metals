package tests.pc

import coursierapi._
import tests.BaseSignatureHelpSuite

class HKSignatureHelpSuite extends BaseSignatureHelpSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    Seq(Dependency.of("org.typelevel", s"cats-core_$binaryVersion", "2.8.0"))

  }

  check(
    "foldmap",
    """import cats.implicits._
      |import cats._
      |object a {
      |  Foldable[Option].foldMap(a @@)
      |}
      |""".stripMargin,
    """|foldMap[A, B](fa: Option[A])(f: A => B)(implicit B: Monoid[B]): B
       |              ^^^^^^^^^^^^^
       |""".stripMargin,
    compat = Map(
      "3" ->
        """|foldMap[A, B](fa: Option[A])(f: A => B)(using B: cats.kernel.Monoid[B]): B
           |              ^^^^^^^^^^^^^
           |""".stripMargin
    ),
  )

}

package tests.pc

import tests.BaseSignatureHelpSuite
import coursierapi._
import tests.BuildInfoVersions
import tests.ScalaDependencies

class HKSignatureHelpSuite extends BaseSignatureHelpSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = ScalaDependencies.createBinaryVersion(scalaVersion)
    if (ScalaDependencies.isScala3Version(scalaVersion)) { Seq.empty }
    else {
      Seq(Dependency.of("org.typelevel", s"cats-core_$binaryVersion", "2.0.0"))
    }
  }

  // @tgodzik TODO currently not implemented for Dotty
  override def excludedScalaVersions: Set[String] =
    Set(BuildInfoVersions.scala3)

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
       |""".stripMargin
  )

}

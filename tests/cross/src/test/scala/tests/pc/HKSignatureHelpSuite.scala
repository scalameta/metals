package tests.pc

import coursierapi._
import tests.BaseSignatureHelpSuite
import tests.BuildInfoVersions

class HKSignatureHelpSuite extends BaseSignatureHelpSuite {

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    if (isScala3Version(scalaVersion)) { Seq.empty }
    else {
      Seq(Dependency.of("org.typelevel", s"cats-core_$binaryVersion", "2.0.0"))
    }
  }

  override def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala3Versions.toSet

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

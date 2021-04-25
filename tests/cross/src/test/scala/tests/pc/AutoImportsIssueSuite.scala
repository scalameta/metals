package tests.pc

import coursierapi.Dependency
import tests.BaseAutoImportsSuite
import tests.BuildInfoVersions

class AutoImportsIssueSuite extends BaseAutoImportsSuite {

  override def excludedScalaVersions: Set[String] =
    BuildInfoVersions.scala3Versions.toSet

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion = createBinaryVersion(scalaVersion)
    if (isScala3Version(scalaVersion)) { Seq.empty }
    else {
      Seq(
        Dependency.of(
          "com.typesafe.akka",
          s"akka-actor-typed_$binaryVersion",
          "2.6.13"
        )
      )
    }
  }

  checkEdit(
    "akka-import-2736",
    """|import akka.io.Dns.Command
       |
       |object Greeter {
       |  <<Address>>("http", "Test", "TestNodeHostName", 1234)
       |}
       |""".stripMargin,
    """|import akka.io.Dns.Command
       |import akka.actor.Address
       |
       |object Greeter {
       |  Address("http", "Test", "TestNodeHostName", 1234)
       |}
       |""".stripMargin
  )

}

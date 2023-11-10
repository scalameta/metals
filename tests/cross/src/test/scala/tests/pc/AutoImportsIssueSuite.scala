package tests.pc

import coursierapi.Dependency
import tests.BaseAutoImportsSuite

class AutoImportsIssueSuite extends BaseAutoImportsSuite {

  override protected def ignoreScalaVersion: Option[IgnoreScalaVersion] = Some(
    IgnoreScala211
  )

  override def extraDependencies(scalaVersion: String): Seq[Dependency] = {
    val binaryVersion =
      if (isScala3Version(scalaVersion)) "2.13"
      else createBinaryVersion(scalaVersion)

    Seq(
      Dependency.of(
        "com.typesafe.akka",
        s"akka-actor-typed_$binaryVersion",
        "2.6.13"
      )
    )
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

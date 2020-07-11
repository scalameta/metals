package tests.feature

import scala.concurrent.Future

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.InitializationOptions

import tests.BaseCompletionLspSuite

class DefinitionCrossLspSuite
    extends BaseCompletionLspSuite("definition-cross") {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(
      InitializationOptions.Default.copy(
        statusBarProvider = Some("show-message")
      )
    )

  if (super.isValidScalaVersionForEnv(BuildInfo.scala211)) {
    test("2.11") {
      basicDefinitionTest(BuildInfo.scala211)
    }
  }

  if (super.isValidScalaVersionForEnv(BuildInfo.scala213)) {
    test("2.13") {
      basicDefinitionTest(BuildInfo.scala213)
    }
  }

  test("underscore") {
    cleanDatabase()
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${BuildInfo.scala213}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |
           |class Main {
           |  val tests = new Test
           |  tests.dummy()
           |}
           |/a/src/main/scala/a/Test.scala
           |package a
           |
           |class Test{
           |  val x = 100_000
           |  def dummy() = x
           |}
           |""".stripMargin
      )
      _ = server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.didOpen("a/src/main/scala/a/Test.scala")
      _ = server.assertReferenceDefinitionBijection()
    } yield ()
  }

  def basicDefinitionTest(scalaVersion: String): Future[Unit] = {
    cleanDatabase()
    for {
      _ <- server.initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "${scalaVersion}"
           |  }
           |}
           |/a/src/main/scala/a/Main.scala
           |object Main {
           |  println("hello!")
           |}
           |""".stripMargin
      )
      _ = client.messageRequests.clear()
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ = server.workspaceDefinitions // trigger definition
      _ <- server.didOpen("scala/Predef.scala")
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        "Preparing presentation compiler"
      )
      _ = assertNoDiff(client.workspaceDiagnostics, "")
    } yield ()
  }
}

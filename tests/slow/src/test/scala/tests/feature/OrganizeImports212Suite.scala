package tests.codeactions

import scala.meta.internal.metals.codeactions.SourceOrganizeImports
import scala.meta.internal.metals.{BuildInfo => V}

class OrganizeImports212Suite
    extends BaseCodeActionLspSuite("OrganizeImports") {

  testLSP("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |"a": { "scalaVersion": "${V.scala212}", "scalacOptions": ["-Ywarn-unused"] }
           |}
           |/.scalafix.conf
           |rules = [
           |  OrganizeImports,
           |]
           |OrganizeImports.targetDialect = Scala2
           |/a/src/main/scala/a/A.scala
           |package a
           |import scala.util.{Success, Failure, Try}
           |
           |object A {
           |  val x: Try[Int] = Success(1)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|a/src/main/scala/a/A.scala:2:29: warning: Unused import
           |import scala.util.{Success, Failure, Try}
           |                            ^^^^^^^
           |""".stripMargin,
      )
      codeActions <- server.assertCodeAction(
        "a/src/main/scala/a/A.scala",
        """|package a
           |<<import scala.util.{Success, Failure, Try}>>
           |
           |object A {
           |  val x: Try[Int] = Success(1)
           |}
           |""".stripMargin,
        SourceOrganizeImports.title,
        List(SourceOrganizeImports.kind),
      )
      _ <- client.applyCodeAction(0, codeActions, server)
      _ = assertNoDiff(
        server.bufferContents("a/src/main/scala/a/A.scala"),
        """|package a
           |import scala.util.Success
           |import scala.util.Try
           |
           |object A {
           |  val x: Try[Int] = Success(1)
           |}
           |""".stripMargin,
      )
    } yield ()
  }
}

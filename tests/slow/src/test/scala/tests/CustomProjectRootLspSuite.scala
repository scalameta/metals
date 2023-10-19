package tests
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.{BuildInfo => V}

class CustomProjectRootLspSuite
    extends BaseLspSuite("custom-project-root", BloopImportInitializer) {
  override def userConfig: UserConfiguration =
    UserConfiguration().copy(customProjectRoot = Some("inner/inner/"))

  test("basic") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""|/build.sbt
            |scalaVersion := "${V.scala213}"
            |/inner/inner/project.scala
            |//> using scala ${V.scala3}
            |/inner/inner/Main.scala
            |package a
            |
            |val k = 1
            |val m: Int = "aaa"
            |""".stripMargin
      )
      _ <- server.didOpen("inner/inner/Main.scala")
      _ = assert(server.server.bspSession.exists(_.main.isScalaCLI))
      _ = assertEquals(
        client.workspaceDiagnostics,
        """|inner/inner/Main.scala:4:14: error: Found:    ("aaa" : String)
           |Required: Int
           |val m: Int = "aaa"
           |             ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}

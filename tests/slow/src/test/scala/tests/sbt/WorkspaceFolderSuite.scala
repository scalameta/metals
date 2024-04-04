package tests.sbt

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.builds.SbtDigest
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.DidChangeWorkspaceFoldersParams
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceFoldersChangeEvent
import tests.BaseImportSuite
import tests.QuickBuild

class WorkspaceFolderSuite extends BaseImportSuite("sbt-workspace-suite") {

  def projectRoot: AbsolutePath = workspace.resolve("main-folder")

  def buildTool: SbtBuildTool =
    SbtBuildTool(None, projectRoot, () => userConfig)
  override def currentDigest(workspace: AbsolutePath): Option[String] =
    SbtDigest.current(projectRoot)

  test("add-workspace-project") {
    cleanWorkspace()
    val libraryFolder = "library-folder"

    writeLayout(
      s"""|/$libraryFolder/project/build.properties
          |sbt.version=${V.sbtVersion}
          |/$libraryFolder/project/plugins.sbt
          |addSbtPlugin("ch.epfl.scala" % "sbt-bloop" % "${V.sbtBloopVersion}")
          |/$libraryFolder/build.sbt
          |scalaVersion := "${V.scala213}"
          |lazy val libraryProject = project.in(file("."))
          |/$libraryFolder/metals.json
          |{
          |  "libraryProject": {
          |    "scalaVersion": "${V.scala213}"
          |  }
          |}
          |/$libraryFolder/src/main/scala/example/A.scala
          |package example
          |object A {
          |  val i = 3
          |}
          |""".stripMargin
    )

    QuickBuild.bloopInstall(workspace.resolve(libraryFolder))

    for {
      _ <- initialize(
        Map(
          // so the fallback service isn't `main-folder`
          "other-fake-folder" ->
            s"""|/project/build.properties
                |sbt.version=${V.sbtVersion}
                |/build.sbt
                |scalaVersion := "${V.scala213}"
                |""".stripMargin,
          "main-folder" ->
            s"""|/project/build.properties
                |sbt.version=${V.sbtVersion}
                |/build.sbt
                |scalaVersion := "${V.scala213}"
                |lazy val root = project.in(file(".")).dependsOn(ProjectRef(file("../$libraryFolder"), "libraryProject"))
                |/src/main/scala/a/Main.scala
                |package a
                |import example.A
                |object Main {
                |  val j: Int = A.i
                |}
                |""".stripMargin,
        ),
        expectError = false,
      )
      _ <- server.didOpen("main-folder/src/main/scala/a/Main.scala")
      _ = assertNoDiagnostics()
      _ <- server.fullServer
        .didChangeWorkspaceFolders(
          new DidChangeWorkspaceFoldersParams(
            new WorkspaceFoldersChangeEvent(
              List(
                new WorkspaceFolder(
                  workspace.resolve(libraryFolder).toURI.toString(),
                  libraryFolder,
                )
              ).asJava,
              Nil.asJava,
            )
          )
        )
        .asScala
      _ = assertEquals(
        server.fullServer.folderServices.size,
        2,
        "should not create new folder service for project ref",
      )
      _ <- server.didOpen(s"$libraryFolder/src/main/scala/example/A.scala")
      _ <- server.didChange(s"$libraryFolder/src/main/scala/example/A.scala")(
        _ => """|package example
                |object A {
                |  val i: String = 3
                |}
                |""".stripMargin
      )
      _ <- server.didSave(s"$libraryFolder/src/main/scala/example/A.scala")(
        identity
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|library-folder/src/main/scala/example/A.scala:3:19: error: type mismatch;
           | found   : Int(3)
           | required: String
           |  val i: String = 3
           |                  ^
           |""".stripMargin,
      )
    } yield ()
  }
}

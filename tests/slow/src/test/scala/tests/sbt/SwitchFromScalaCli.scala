package tests.sbt

import java.io.File

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.scalacli.ScalaCli
import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite
import tests.BloopImportInitializer

class SwitchFromScalaCli
    extends BaseLspSuite(
      "switching-from-scalacli-to-sbt",
      BloopImportInitializer,
    ) {

  test("switch-when-scala-cli-running") {
    cleanWorkspace()
    writeLayout(
      """|/a/src/main/scala/A.scala
         |
         |object A{
         |  val foo = 1
         |  foo + foo
         |}
         |""".stripMargin
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      _ <- server.server.scalaCli.start(server.server.folder)
      _ = assert(server.server.scalaCli.path.contains(server.server.folder))
      _ = writeLayout(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |ThisBuild / scalaVersion := "${V.scala213}"
            |val a = project.in(file("a"))
            |""".stripMargin
      )
      _ = client.importBuild = ImportBuild.yes
      _ <- server.didOpen("build.sbt")
      _ <- server.didSave("build.sbt")(identity)
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        s"""|${Messages.ImportBuild.newBuildTool("sbt")}
            |${Messages.bloopInstallProgress("sbt").message}""".stripMargin,
      )
      _ = assert(server.server.bspSession.exists(_.main.isBloop))
      _ = assert(server.server.scalaCli.path.isEmpty)
    } yield ()
  }

  test("switch-when-scala-cli-explicitly-chosen") {
    cleanWorkspace()
    writeLayout(
      s"""|/.bsp/scala-cli.json
          |${ScalaCliBsp.scalaCliBspJsonContent()}
          |/a/src/main/scala/A.scala
          |
          |object A{
          |  val foo = 1
          |  foo + foo
          |}
          |""".stripMargin
    )
    for {
      _ <- server.initialize()
      _ <- server.initialized()
      // we explicitly choose scala-cli
      _ = server.server.tables.buildServers.chooseServer("scala-cli")
      _ = assert(server.server.bspSession.exists(_.main.isScalaCLI))
      _ = writeLayout(
        s"""|/project/build.properties
            |sbt.version=${V.sbtVersion}
            |/build.sbt
            |ThisBuild / scalaVersion := "${V.scala213}"
            |val a = project.in(file("a"))
            |""".stripMargin
      )
      _ = client.importBuild = ImportBuild.yes
      _ <- server.didOpen("build.sbt")
      _ <- server.didSave("build.sbt")(identity)
      _ = assertNoDiff(
        client.workspaceMessageRequests,
        s"""|${Messages.ImportBuild.newBuildTool("sbt")}
            |${Messages.bloopInstallProgress("sbt").message}""".stripMargin,
      )
      _ = assert(server.server.bspSession.exists(_.main.isBloop))
    } yield ()
  }
}

object ScalaCliBsp {
  def scalaCliBspJsonContent(): String = {
    val argv = List(
      ScalaCli.javaCommand,
      "-cp",
      ScalaCli.scalaCliClassPath().mkString(File.pathSeparator),
      ScalaCli.scalaCliMainClass,
      "bsp",
      ".",
    )
    val bsjJson = ujson.Obj(
      "name" -> "scala-cli",
      "argv" -> argv,
      "version" -> BuildInfo.scalaCliVersion,
      "bspVersion" -> "2.0.0",
      "languages" -> List("scala", "java"),
    )
    ujson.write(bsjJson)
  }
}

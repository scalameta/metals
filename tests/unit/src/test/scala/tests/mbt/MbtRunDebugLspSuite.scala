package tests.mbt

import scala.concurrent.Future

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BaseCodeLensLspSuite
import tests.BuildInfo
import tests.Library

class MbtRunDebugLspSuite extends BaseCodeLensLspSuite("mbt-run-debug") {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      referenceProvider = ReferenceProviderConfig.mbt,
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  override def initializeGitRepo: Boolean = true

  test("discover-run-main-classes") {
    cleanWorkspace()
    val scalaLibJarUri =
      Library
        .getScalaLibraryJarPath(BuildInfo.scalaVersion)
        .toURI
        .toString

    val mbtJson =
      s"""|{
          |  "dependencyModules": [
          |    {
          |      "id": "org.scala-lang:scala-library:${BuildInfo.scalaVersion}",
          |      "jar": "$scalaLibJarUri"
          |    }
          |  ],
          |  "namespaces": {
          |    "core": {
          |      "sources": ["src/"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}",
          |      "dependencyModules": [
          |        "org.scala-lang:scala-library:${BuildInfo.scalaVersion}"
          |      ]
          |    }
          |  }
          |}""".stripMargin

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/src/ScalaMain.scala
            |package example
            |
            |object ScalaMain {
            |  def main(args: Array[String]): Unit = ()
            |}
            |/src/AppMain.scala
            |package example
            |
            |object AppMain extends App
            |/src/JavaMain.java
            |package example;
            |
            |public class JavaMain {
            |  public static void main(String[] args) {
            |  }
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen("src/ScalaMain.scala")
      _ <- server.didOpen("src/AppMain.scala")
      _ <- server.didOpen("src/JavaMain.java")
      _ <- server.server.buildTargetClasses.rebuildIndex(
        server.server.buildTargets.allBuildTargetIds
      )
      _ <- Future.sequence(
        List(
          assertCodeLenses(
            "src/ScalaMain.scala",
            """|package example
               |
               |<<run>><<debug>>
               |object ScalaMain {
               |  def main(args: Array[String]): Unit = ()
               |}
               |""".stripMargin,
          ),
          assertCodeLenses(
            "src/AppMain.scala",
            """|package example
               |
               |<<run>><<debug>>
               |object AppMain extends App
               |""".stripMargin,
          ),
          assertCodeLenses(
            "src/JavaMain.java",
            """|package example;
               |
               |public class JavaMain {
               |<<run>><<debug>>
               |  public static void main(String[] args) {
               |  }
               |}
               |""".stripMargin,
          ),
        )
      )
    } yield ()
  }
}

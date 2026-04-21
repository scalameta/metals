package tests.mbt

import java.nio.file.Files

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import coursierapi.Dependency
import coursierapi.Fetch
import tests.BaseCompletionLspSuite
import tests.BuildInfo
import tests.Library
import tests.TestHovers

/**
 * End-to-end checks for `.metals/mbt.json` with the built-in MBT BSP server:
 * connection, presentation compiler hover, goto definition, and completions
 * across two namespaces (each with multiple source files).
 */
class MbtBuildServerLspSuite
    extends BaseCompletionLspSuite("mbt-build-server")
    with TestHovers {

  override def userConfig: UserConfiguration =
    super.userConfig.copy(
      fallbackScalaVersion = Some(BuildInfo.scalaVersion),
      presentationCompilerDiagnostics = true,
      buildOnChange = false,
      buildOnFocus = false,
      workspaceSymbolProvider = WorkspaceSymbolProviderConfig.mbt,
      referenceProvider = ReferenceProviderConfig.mbt,
      fallbackSourcepath = FallbackSourcepathConfig("all-sources"),
      preferredBuildServer = Some(MbtBuildServer.name),
      automaticImportBuild = AutoImportBuildKind.All,
    )

  override def initializeGitRepo: Boolean = true

  private def targetIds: Set[String] =
    server.server.buildTargets.allBuildTargetIds.map(_.getUri).toSet

  test("script-import-then-mbt-server") {
    cleanWorkspace()
    val scalaBinary = BuildInfo.scalaVersion.split("\\.").take(2).mkString(".")
    val xmlJar = Fetch
      .create()
      .withMainArtifacts()
      .withDependencies(
        Dependency
          .of("org.scala-lang.modules", s"scala-xml_$scalaBinary", "2.3.0")
          .withTransitive(false)
      )
      .fetch()
      .asScala
      .map(_.toPath)
      .head
    val mbtJson =
      s"""|{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["src/**"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}",
          |      "dependencyModules": [
          |        {
          |          "id": "org.scala-lang.modules:scala-xml_$scalaBinary:2.3.0",
          |          "jar": "$xmlJar"
          |        }
          |      ]
          |    }
          |  }
          |}""".stripMargin
    val script =
      s"""|#!/bin/sh
          |printf '$mbtJson' > "$$MBT_OUTPUT_FILE"
          |""".stripMargin

    for {
      _ <- initialize(
        s"""|/build.mbt.sh
            |$script
            |/src/Main.scala
            |package example
            |
            |import scala.xml.XML
            |
            |object Main {
            |  val doc = XML.loadString("<root/>")
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didChangeWatchedFiles(".metals/mbt.json")
      _ <- server.didOpen("src/Main.scala")
      _ <- server.assertHover(
        "src/Main.scala",
        """|package example
           |
           |import scala.xml.XML
           |
           |object Main {
           |  val doc = XML.load@@String("<root/>")
           |}""".stripMargin,
        """|```scala
           |def loadString(string: String): Elem
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("two-targets-hover-definition-completion") {
    cleanWorkspace()
    val scalaLibJar =
      Library
        .getScalaLibraryJarPath(BuildInfo.scalaVersion)
        .toString()
        .replace("\\", "\\\\")

    val mbtJson =
      s"""{
         |  "namespaces": {
         |    "core": {
         |      "sources": ["core/src/**"],
         |      "scalaVersion": "${BuildInfo.scalaVersion}",
         |      "dependencyModules": [
         |        {
         |          "id": "org.scala-lang:scala-library:${BuildInfo.scalaVersion}",
         |          "jar": "$scalaLibJar"
         |        }
         |      ]
         |    },
         |    "extra": {
         |      "sources": ["extra/src"],
         |      "scalaVersion": "${BuildInfo.scalaVersion}",
         |      "dependencyModules": [
         |        {
         |          "id": "org.scala-lang:scala-library:${BuildInfo.scalaVersion}",
         |          "jar": "$scalaLibJar"
         |        }
         |      ],
         |      "dependsOn": [
         |        "core"
         |      ]
         |    }
         |  }
         |}""".stripMargin

    val coreModel = "core/src/core/Model.scala"
    val coreService = "core/src/core/Service.scala"
    val extraHelper = "extra/src/extra/Helper.scala"
    val extraApp = "extra/src/extra/App.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/$coreModel
            |package core
            |
            |import core.service.Service
            |
            |object Model {
            |  def answer = Service.text
            |}
            |/$coreService
            |package core.service
            |
            |import core.Model
            |
            |object Service {
            |  def text: String = Model.answer.toString
            |}
            |/$extraHelper
            |package extra
            |
            |object Helper {
            |  def label: String = "ok"
            |}
            |/$extraApp
            |package extra
            |import core.Model
            |
            |object App {
            |  def run() = Model.answer
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen(coreModel)
      _ <- server.didOpen(coreService)
      _ <- server.didOpen(extraHelper)
      _ <- server.didOpen(extraApp)
      _ = assertNoDiagnostics()
      _ <- server.assertHover(
        coreModel,
        """|package core
           |
           |import core.service.Service
           |
           |object Model {
           |  def answ@@er = Service.text
           |}""".stripMargin,
        """|```scala
           |def answer: String
           |```
           |""".stripMargin.hover,
      )
      _ <- server.assertDefinition(
        coreService,
        "Mod@@el.answer.toString",
        s"""|$coreModel:5:8: definition
            |object Model {
            |       ^^^^^
            |""".stripMargin,
      )
      _ <- server.didFocus(coreService)
      _ <- assertCompletion(
        """|package core
           |
           |import core.service.Service
           |
           |object Model {
           |  def answer = Service.text@@
           |}""".stripMargin,
        "text: String",
        filename = Some(coreModel),
      )
      _ <- server.assertHover(
        extraApp,
        """|package extra
           |import core.Model
           |
           |object App {
           |  def r@@un() = Model.answer
           |}""".stripMargin,
        """|```scala
           |def run(): String
           |```
           |""".stripMargin.hover,
      )
      _ <- server.assertDefinition(
        extraApp,
        "Mod@@el.answer",
        s"""|$coreModel:5:8: definition
            |object Model {
            |       ^^^^^
            |""".stripMargin,
      )
      _ <- server.didFocus(extraApp)
      _ <- assertCompletion(
        """|package extra
           |import core.Model
           |
           |object App {
           |  def run() = Model.answer@@
           |}""".stripMargin,
        "answer: String",
        filename = Some(extraApp),
      )
    } yield ()
  }

  test("mbt-json-change-triggers-workspace-reload") {
    cleanWorkspace()
    val initialMbtJson =
      s"""|{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["core/src/**"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}"
          |    }
          |  }
          |}
          |""".stripMargin
    val updatedMbtJson =
      s"""|{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["core/src/**"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}"
          |    },
          |    "extra": {
          |      "sources": ["extra/src/**"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}",
          |      "dependsOn": ["core"]
          |    }
          |  }
          |}
          |""".stripMargin

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$initialMbtJson
            |/core/src/core/Model.scala
            |package core
            |
            |object Model
            |/extra/src/extra/App.scala
            |package extra
            |
            |object App
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ = assertEquals(targetIds, Set("mbt://namespace/core"))
      _ = Files.writeString(
        workspace.resolve(".metals").resolve("mbt.json").toNIO,
        updatedMbtJson,
      )
      _ <- server.didChangeWatchedFiles(".metals/mbt.json")
      _ = assertEquals(
        targetIds,
        Set("mbt://namespace/core", "mbt://namespace/extra"),
      )
    } yield ()
  }

}

package tests.mbt

import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.Properties

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer

import tests.BaseCompletionLspSuite
import tests.BuildInfo
import tests.MbtJsonBuilder

class MbtBuildServerManualImportLspSuite
    extends BaseCompletionLspSuite("mbt-build-server-manual-import") {

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
      automaticImportBuild = AutoImportBuildKind.Off,
    )

  if (!Properties.isWin)
    test("script-import-clears-java-diagnostics") {
      runScriptImportClearsJavaDiagnostics()
    }

  private def runScriptImportClearsJavaDiagnostics(): Future[Unit] = {
    cleanWorkspace()
    val mainFile = "src/main/java/a/SampleProfileApplication.java"
    val firstExtraFile = "src/main/java/a/FirstExtra.java"
    val secondExtraFile = "src/main/java/a/SecondExtra.java"
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addJavaDependency("com.google.guava", "guava", "33.5.0-jre")
      .addNamespace("core", List("src/**"))
      .build()
    val script =
      s"""|#!/bin/sh
          |sleep 1
          |printf '%s' '$mbtJson' > "$$MBT_OUTPUT_FILE"
          |""".stripMargin
    def fileInput(className: String): String =
      s"""|package a;
          |
          |import com.google.common.collect.ImmutableList;
          |
          |public class $className {
          |  public static ImmutableList<String> names = ImmutableList.of("Alice", "Bob");
          |}
          |""".stripMargin

    client.showMessageRequestHandler = params =>
      if (params.getMessage.startsWith("New MBT"))
        params.getActions.asScala.find(_.getTitle == "Not now")
      else None

    for {
      _ <- initialize(
        s"""|/build.mbt.sh
            |$script
            |/$mainFile
            |${fileInput("SampleProfileApplication")}
            |/$firstExtraFile
            |${fileInput("FirstExtra")}
            |/$secondExtraFile
            |${fileInput("SecondExtra")}
            |""".stripMargin,
        expectError = true,
      )
      _ <- server.didOpen(mainFile)
      _ <- server.didFocus(mainFile)
      _ = assert(
        client.workspaceDiagnostics.nonEmpty,
        "Expected diagnostics before MBT import",
      )
      importBuild = server.executeCommand(ServerCommands.ImportBuild)
      _ <- server.didOpen(firstExtraFile)
      _ <- server.didOpen(secondExtraFile)
      _ <- importBuild
      _ = assertConnectedToBuildServer("MBT")
      _ = assertNoDiagnostics()
    } yield ()
  }
}

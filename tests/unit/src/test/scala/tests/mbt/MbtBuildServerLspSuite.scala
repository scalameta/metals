package tests.mbt

import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.concurrent.Future
import scala.jdk.CollectionConverters._
import scala.util.Properties

import scala.meta.internal.metals.AutoImportBuildKind
import scala.meta.internal.metals.Configs.FallbackSourcepathConfig
import scala.meta.internal.metals.Configs.ReferenceProviderConfig
import scala.meta.internal.metals.Configs.WorkspaceSymbolProviderConfig
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.TestUserInterfaceKind
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.internal.mtags.ScalametaCommonEnrichments._

import coursierapi.Dependency
import coursierapi.Fetch
import org.eclipse.lsp4j.FileChangeType
import tests.BaseCompletionLspSuite
import tests.BuildInfo
import tests.Library
import tests.MbtJsonBuilder
import tests.TestHovers
import tests.TestingServer

/**
 * End-to-end checks for `.metals/mbt.json` with the built-in MBT BSP server:
 * connection, presentation compiler hover, goto definition, and completions
 * across two namespaces (each with multiple source files).
 */
class MbtBuildServerLspSuite
    extends BaseCompletionLspSuite("mbt-build-server")
    with TestHovers {

  override protected def initializationOptions: Option[InitializationOptions] =
    Some(InitializationOptions.Default.copy(testExplorerProvider = Some(true)))

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
      testUserInterface = TestUserInterfaceKind.TestExplorer,
    )

  override def initializeGitRepo: Boolean = true

  private def targetIds: Set[String] =
    server.server.buildTargets.allBuildTargetIds.map(_.getUri).toSet

  if (!Properties.isWin)
    test("script-import-then-mbt-server") {
      cleanWorkspace()
      val scalaBinary =
        BuildInfo.scalaVersion.split("\\.").take(2).mkString(".")
      val xmlJarUri = Fetch
        .create()
        .withMainArtifacts()
        .withDependencies(
          Dependency
            .of("org.scala-lang.modules", s"scala-xml_$scalaBinary", "2.3.0")
            .withTransitive(false)
        )
        .fetch()
        .asScala
        .map(_.toPath.toUri.toString)
        .head
      val mbtJson =
        s"""|{
            |  "dependencyModules": [
            |    {
            |      "id": "org.scala-lang.modules:scala-xml_$scalaBinary:2.3.0",
            |      "jar": "$xmlJarUri"
            |    }
            |  ],
            |  "namespaces": {
            |    "core": {
            |      "sources": ["src/**"],
            |      "scalaVersion": "${BuildInfo.scalaVersion}",
            |      "dependencyModules": [
            |        "org.scala-lang.modules:scala-xml_$scalaBinary:2.3.0"
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

  test("mbt-munit-test-discovery") {
    cleanWorkspace()
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addScalaLibrary()
      .addDependency("org.scalameta", "munit", "0.7.29")
      .addNamespace("test", List("src/**"))
      .build()
    val testFile = "src/MunitTest.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/$testFile
            |package example
            |
            |class MunitTest extends munit.FunSuite {
            |  def complicateMethod(): Unit = {
            |    val a = 1
            |    val b = 2
            |    val c = a + b
            |    println(c)
            |  }
            |  test("ok") {}
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen(testFile)
      testSuites <- server.discoverTestSuites(List(testFile))
    } yield {
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assertNoDiff(
        testEvents.mkString("\n"),
        s"""|AddTestSuite(example.MunitTest,MunitTest,example/MunitTest#,Location [
            |  uri = "${workspace.toURI}src/MunitTest.scala"
            |  range = Range [
            |    start = Position [
            |      line = 2
            |      character = 6
            |    ]
            |    end = Position [
            |      line = 2
            |      character = 15
            |    ]
            |  ]
            |],true)
            |""".stripMargin,
      )
    }
  }

  test("mbt-junit-test-discovery") {
    cleanWorkspace()
    val mbtJson = new MbtJsonBuilder(BuildInfo.scalaVersion)
      .addScalaLibrary()
      .addJavaDependency("junit", "junit", "4.13.2")
      .addNamespace("test", List("src/**"))
      .build()
    val testFile = "src/JunitTest.java"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/$testFile
            |package example;
            |
            |import org.junit.Test;
            |import static org.junit.Assert.*;
            |
            |public class JunitTest {
            |  @Test
            |  public void sampleTest() {
            |    assertTrue(true);
            |  }
            |
            |  @Test
            |  public void anotherTest() {
            |    assertEquals(2, 1 + 1);
            |  }
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen(testFile)
      testSuites <- server.discoverTestSuites(List(testFile))
    } yield {
      val testEvents = testSuites.flatMap(_.events.asScala.toList)
      assertNoDiff(
        testEvents.mkString("\n"),
        s"""|AddTestSuite(example.JunitTest,JunitTest,example/JunitTest#,Location [
            |  uri = "${workspace.toURI}src/JunitTest.java"
            |  range = Range [
            |    start = Position [
            |      line = 5
            |      character = 13
            |    ]
            |    end = Position [
            |      line = 5
            |      character = 22
            |    ]
            |  ]
            |],true)
            |""".stripMargin,
      )
    }
  }

  test("two-targets-hover-definition-completion") {
    cleanWorkspace()
    val scalaLibJarUri =
      Library
        .getScalaLibraryJarPath(BuildInfo.scalaVersion)
        .toURI
        .toString

    val mbtJson =
      s"""{
         |  "dependencyModules": [
         |    {
         |      "id": "org.scala-lang:scala-library:${BuildInfo.scalaVersion}",
         |      "jar": "$scalaLibJarUri"
         |    }
         |  ],
         |  "namespaces": {
         |    "core": {
         |      "sources": ["core/src/**"],
         |      "scalaVersion": "${BuildInfo.scalaVersion}",
         |      "dependencyModules": [
         |        "org.scala-lang:scala-library:${BuildInfo.scalaVersion}"
         |      ]
         |    },
         |    "extra": {
         |      "sources": ["extra/src/**"],
         |      "scalaVersion": "${BuildInfo.scalaVersion}",
         |      "dependencyModules": [
         |        "org.scala-lang:scala-library:${BuildInfo.scalaVersion}"
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
        "def answer = Service.text@@",
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
        "def run() = Model.answer@@",
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

  // Run this test with virtual document support so that JDK sources are served
  // as src.zip! URIs. When a PC request arrives for such a URI, PruneJavaFile
  // materializes the file to .metals/out for --patch-module. The editor then
  // fires didChangeWatchedFiles for the new file (workspace/**/*.java glob
  // matches it). This test verifies those events are silently dropped and the
  // materialized file never enters the MBT index or Scala sourcepath.
  test(
    "metals-out-not-indexed-after-jdk-source-hover"
      .tag(TestingServer.virtualDocTag)
  ) {
    cleanWorkspace()
    val mbtJson = // namespaces might filter out the problematic file
      s"""|{
          |}""".stripMargin

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/src/Foo.scala
            |package example;
            |
            |object Foo {
            |  def show(obj: java.lang.Object): String = {
            |    obj.toString();
            |  }
            |}
            |/src/Dummy.scala
            |package example
            |object Dummy {
            |  def ok: String = ""
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen("src/Foo.scala")
      // Navigate to the definition of toString (a JDK method). In virtual-doc mode
      // this returns a src.zip! URI so the editor can open the JDK source.
      definitions <- server.definitionSubstringQuery(
        "src/Foo.scala",
        "    obj.toStr@@ing();",
      )
      srcZipLoc = definitions.find(_.getUri.contains("src.zip"))
      // Not strictly necessary assertion here, but this is the thing that causes the issue
      _ = assert(
        srcZipLoc.isDefined,
        s"Expected a src.zip! definition for String, got: $definitions",
      )
      // Verify PruneJavaFile actually wrote something to .metals/out.
      metalsOut = workspace.resolve(".metals/out")
      metalsOutFiles = metalsOut.listRecursive.filter(_.isFile).toList
      _ = assert(
        metalsOutFiles.nonEmpty,
        ".metals/out should contain at least one file materialised by PruneJavaFile",
      )
      // Simulate the editor firing didChangeWatchedFiles for each materialised
      // file (workspace/**/*.java glob delivers these events).
      _ <- Future.traverse(metalsOutFiles) { f =>
        server.didChangeWatchedFiles(
          workspace.toNIO.relativize(f.toNIO).toString,
          FileChangeType.Created,
        )
      }
      _ <- Future.traverse(metalsOutFiles) { f =>
        server.didChangeWatchedFiles(
          workspace.toNIO.relativize(f.toNIO).toString
        )
      }
      // Normal workspace features must still work after the spurious events.
      _ = assertNoDiagnostics()
      // crash appeared even after resetting the presentation compiler
      _ <- server.server.resetPresentationCompilers()
      _ <- server.assertHover(
        "src/Foo.scala",
        """|object Foo {
           |  def show(obj: java.lang.Object): String = {
           |    obj.toSt@@ring();
           |  }
           |}""".stripMargin,
        s"""|${"def toString(): String".hover}
            |Returns a string representation of the object.
            |
            |**Returns:** a string representation of the object.""".stripMargin,
      )
      _ <- server.assertHover(
        "src/Dummy.scala",
        """|object Dummy {
           |  def ok: Str@@ing = ""
           |}""".stripMargin,
        s"""|${"type String: String".hover}""".stripMargin,
      )
    } yield ()
  }

  test("mbt-duplicate-source-filenames-in-same-package") {
    cleanWorkspace()
    val mbtJson =
      s"""|{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["main/src/**", "test/src/**"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}"
          |    }
          |  }
          |}""".stripMargin

    val appFile = "main/src/app/App.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/main/src/util/Utils.scala
            |package util
            |
            |object MainUtils {
            |  val value: String = "main"
            |}
            |/test/src/util/Utils.scala
            |package util
            |
            |object TestUtils {
            |  val value: String = "test"
            |}
            |/$appFile
            |package app
            |
            |import util.MainUtils
            |import util.TestUtils
            |
            |object App {
            |  def runMain: String = MainUtils.value
            |  def runTest: String = TestUtils.value
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ <- server.didOpen(appFile)
      _ = assertNoDiagnostics()
      _ <- server.assertHover(
        appFile,
        """|package app
           |
           |import util.MainUtils
           |import util.TestUtils
           |
           |object App {
           |  def runMain: String = MainUtils.val@@ue
           |  def runTest: String = TestUtils.value
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
      _ <- server.assertHover(
        appFile,
        """|package app
           |
           |import util.MainUtils
           |import util.TestUtils
           |
           |object App {
           |  def runMain: String = MainUtils.value
           |  def runTest: String = TestUtils.val@@ue
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("mbt-uncheckedSources-compiles-with-gitignore-sources") {
    cleanWorkspace()
    val mbtJson =
      s"""|{
          |  "uncheckedSources": ["generated"]
          |}""".stripMargin

    val generatedFile = "generated/core/Generated.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/.gitignore
            |generated/
            |/src/core/Main.scala
            |package core
            |
            |object Main {
            |  def main(): Any = GeneratedObject.value
            |}
            |/$generatedFile
            |package core
            |
            |object GeneratedObject {
            |  val value: String = "generated"
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ = assertNoDiff(
        server.workspaceSymbol("GeneratedObject"),
        "core.GeneratedObject",
      )
      _ <- server.didOpen(generatedFile)
      _ <- server.assertHover(
        generatedFile,
        """|package core
           |
           |object Main {
           |  def main(): Any = GeneratedObject.val@@ue
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("mbt-namespace-uncheckedSources-compiles-with-gitignore-sources") {
    cleanWorkspace()
    val mbtJson =
      s"""|{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["src/**"],
          |      "uncheckedSources": ["generated"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}"
          |    }
          |  }
          |}""".stripMargin

    val generatedFile = "generated/core/Generated.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/.gitignore
            |generated/
            |/src/core/Main.scala
            |package core
            |
            |object Main {
            |  def main(): Any = GeneratedObject.value
            |}
            |/$generatedFile
            |package core
            |
            |object GeneratedObject {
            |  val value: String = "generated"
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ = assertNoDiff(
        server.workspaceSymbol("GeneratedObject"),
        "core.GeneratedObject",
      )
      _ <- server.didOpen(generatedFile)
      _ <- server.assertHover(
        generatedFile,
        """|package core
           |
           |object Main {
           |  def main(): Any = GeneratedObject.val@@ue
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test(
    "mbt-namespace-uncheckedSources-visible-in-dependent-namespace"
  ) {
    cleanWorkspace()
    val mbtJson =
      s"""|{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["src/**"],
          |      "uncheckedSources": ["generated"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}"
          |    },
          |    "extra": {
          |      "sources": ["extra/src/**"],
          |      "scalaVersion": "${BuildInfo.scalaVersion}",
          |      "dependsOn": ["core"]
          |    }
          |  }
          |}""".stripMargin

    val generatedFile = "generated/core/Generated.scala"
    val extraApp = "extra/src/extra/App.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |$mbtJson
            |/.gitignore
            |generated/
            |/src/core/Base.scala
            |package core
            |
            |object Base {
            |  val label: String = "base"
            |}
            |/$generatedFile
            |package core
            |
            |object GeneratedObject {
            |  val value: String = "generated"
            |}
            |/$extraApp
            |package extra
            |
            |import core.GeneratedObject
            |
            |object App {
            |  def run(): String = GeneratedObject.value
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ = assertNoDiff(
        server.workspaceSymbol("GeneratedObject"),
        "core.GeneratedObject",
      )
      _ <- server.didOpen(extraApp)
      _ <- server.assertHover(
        extraApp,
        """|package extra
           |
           |import core.GeneratedObject
           |
           |object App {
           |  def run(): String = GeneratedObject.val@@ue
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test("mbt-uncheckedSources-compilers-with-srcjar") {
    cleanWorkspace()
    val srcJarName = "generated-sources.srcjar"
    // Create the srcjar before initialize so it exists when the server first indexes
    val zos = new ZipOutputStream(
      new FileOutputStream(workspace.resolve(srcJarName).toFile)
    )
    zos.putNextEntry(new ZipEntry("core/GeneratedObject.scala"))
    zos.write(
      """|package core
         |
         |object GeneratedObject {
         |  val value: String = "generated"
         |}
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    zos.closeEntry()
    zos.close()

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |{
            |  "uncheckedSources": ["$srcJarName"]
            |}
            |/src/core/Main.scala
            |package core
            |
            |object Main {
            |  def main(): Any = GeneratedObject.value
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ = assertNoDiff(
        server.workspaceSymbol("GeneratedObject"),
        "core.GeneratedObject",
      )
      _ <- server.didOpen("src/core/Main.scala")
      _ <- server.assertHover(
        "src/core/Main.scala",
        """|package core
           |
           |object Main {
           |  def main(): Any = GeneratedObject.val@@ue
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }

  test(
    "mbt-namespace-uncheckedSources-srcjar-visible-in-dependent-namespace"
  ) {
    cleanWorkspace()
    val srcJarName = "generated-sources.srcjar"
    val zos = new ZipOutputStream(
      new FileOutputStream(workspace.resolve(srcJarName).toFile)
    )
    zos.putNextEntry(new ZipEntry("core/GeneratedObject.scala"))
    zos.write(
      """|package core
         |
         |object GeneratedObject {
         |  val value: String = "generated"
         |}
         |""".stripMargin.getBytes(StandardCharsets.UTF_8)
    )
    zos.closeEntry()
    zos.close()

    val extraApp = "extra/src/extra/App.scala"

    for {
      _ <- initialize(
        s"""|/.metals/mbt.json
            |{
            |  "namespaces": {
            |    "core": {
            |      "sources": ["src/**"],
            |      "uncheckedSources": ["$srcJarName"],
            |      "scalaVersion": "${BuildInfo.scalaVersion}"
            |    },
            |    "extra": {
            |      "sources": ["extra/src/**"],
            |      "scalaVersion": "${BuildInfo.scalaVersion}",
            |      "dependsOn": ["core"]
            |    }
            |  }
            |}
            |/src/core/Base.scala
            |package core
            |
            |object Base {
            |  val label: String = "base"
            |}
            |/$extraApp
            |package extra
            |
            |import core.GeneratedObject
            |
            |object App {
            |  def run(): String = GeneratedObject.value
            |}
            |""".stripMargin
      )
      _ = assertConnectedToBuildServer("MBT")
      _ = assertNoDiff(
        server.workspaceSymbol("GeneratedObject"),
        "core.GeneratedObject",
      )
      _ <- server.didOpen(extraApp)
      _ <- server.assertHover(
        extraApp,
        """|package extra
           |
           |import core.GeneratedObject
           |
           |object App {
           |  def run(): String = GeneratedObject.val@@ue
           |}""".stripMargin,
        """|```scala
           |val value: String
           |```
           |""".stripMargin.hover,
      )
    } yield ()
  }
}

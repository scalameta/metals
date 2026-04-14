package tests.mbt

import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.JsonParser.XtensionSerializableToJson
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.mbt.MbtBuild
import scala.meta.internal.metals.mbt.MbtBuildServer
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.SourceItemKind
import ch.epfl.scala.bsp4j.SourcesParams
import com.google.gson.GsonBuilder
import tests.FileLayout

class MbtBuildSuite extends tests.BaseSuite {

  test("legacy-flat-format") {
    val dir = Files.createTempDirectory("mbt-legacy")
    val f = dir.resolve("mbt.json")
    val initialContent =
      """{
        |  "dependencyModules": [
        |    {
        |      "id": "org.scala-lang:scala-library:2.13.16",
        |      "jar": "/tmp/scala-library.jar"
        |    }
        |  ]
        |}""".stripMargin
    Files.writeString(
      f,
      initialContent,
    )
    val build = MbtBuild.fromFile(f)

    val prettyGson = new GsonBuilder().setPrettyPrinting().create()
    assertNoDiff(prettyGson.toJson(build.toJsonObject), initialContent)

    assert(!build.isEmpty)
    assertEquals(build.getNamespaces.size(), 0)

    val asBsp = build.asBspModules
    assertEquals(asBsp.getItems().size(), 1)
    assertEquals(
      asBsp.getItems().get(0).getTarget().getUri(),
      MbtBuild.LegacyTargetName,
    )
  }

  test("namespaces-format") {
    val dir = Files.createTempDirectory("mbt-ns")
    val f = dir.resolve("mbt.json")
    val initialContent =
      """|{
         |  "dependencyModules": [],
         |  "namespaces": {
         |    "core": {
         |      "sources": [
         |        "./src"
         |      ],
         |      "compilerOptions": [
         |        "-release",
         |        "11"
         |      ],
         |      "dependencyModules": [
         |        {
         |          "id": "org.scala-lang:scala-library:2.13.16",
         |          "jar": "/tmp/scala-library.jar"
         |        }
         |      ]
         |    },
         |    "extra": {
         |      "sources": [
         |        "./src/**"
         |      ],
         |      "dependencyModules": [
         |        {
         |          "id": "org.scala-lang:scala-library:2.13.16",
         |          "jar": "/tmp/scala-library.jar"
         |        }
         |      ],
         |      "dependsOn": [
         |        "core"
         |      ]
         |    }
         |  }
         |}
         |""".stripMargin
    Files.writeString(
      f,
      initialContent,
    )
    val build = MbtBuild.fromFile(f)

    val prettyGson = new GsonBuilder().setPrettyPrinting().create()
    assertNoDiff(prettyGson.toJson(build.toJsonObject), initialContent)

    assert(!build.isEmpty)
    assertEquals(build.getNamespaces.size(), 2)

    val targets = build.mbtTargets.map(
      _.buildTarget(AbsolutePath(dir), ScalaVersionSelector.default)
    )
    assertEquals(
      targets
        .map(_.getId().getUri().toString)
        .toSet,
      Set("mbt://namespace/core", "mbt://namespace/extra"),
    )
    val extraTarget = targets
      .find(_.getDisplayName == "extra")
      .getOrElse(fail("missing extra target"))
    assertEquals(
      extraTarget.getDependencies.asScala.map(_.getUri).toSeq.sorted,
      Seq("mbt://namespace/core"),
    )
  }

  test("glob-inverse-sources") {
    val dir = Files.createTempDirectory("mbt-glob")
    val ws = AbsolutePath(dir)
    val scalaPath = ws.resolve("src/main/scala/a/B.scala")
    val javaPath = ws.resolve("src/main/scala/a/C.java")
    Files.createDirectories(scalaPath.parent.toNIO)
    Files.createFile(scalaPath.toNIO)
    Files.createFile(javaPath.toNIO)

    val f = dir.resolve("mbt.json")
    Files.writeString(
      f,
      """|{
         |  "namespaces": {
         |    "app": {
         |      "sources": ["./src/**/*.scala"],
         |      "dependencyModules": []
         |    }
         |  }
         |}
         |""".stripMargin,
    )
    val build = MbtBuild.fromFile(f)
    assertEquals(
      build.mbtTargets
        .filter(_.containsSource(ws, scalaPath))
        .map(_.id)
        .map(_.getUri)
        .toSeq,
      Seq("mbt://namespace/app"),
    )
    assertEquals(
      build.mbtTargets.filter(_.containsSource(ws, javaPath)).length,
      0,
    )
    val sourceItems = build.mbtTargets.map(_.sourcesItem(ws))
    val appSources = sourceItems
      .find(_.getTarget.getUri == "mbt://namespace/app")
      .map(_.getSources.asScala.toSeq)
      .getOrElse(fail("missing app sources"))
    assertEquals(appSources.size, 0)
  }

  test("glob-prefixes-prune-directory-scan") {
    val workspace = AbsolutePath(Files.createTempDirectory("prefixing-globs"))
    FileLayout.fromString(
      s"""|/.metals/mbt.json
          |{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["core/src/**"],
          |      "scalaVersion": "${BuildInfo.scala213}"
          |    },
          |    "shared": {
          |      "sources": ["**/shared/**"],
          |      "scalaVersion": "${BuildInfo.scala213}"
          |    }
          |  }
          |}
          |""".stripMargin,
      root = workspace,
    )

    val targets = MbtBuild.fromWorkspace(workspace).mbtTargets
    val core = targets.find(_.name == "core").get
    val shared = targets.find(_.name == "shared").get

    assertEquals(
      core.globMatchers.head.prefix.map(_.toString.replace('\\', '/')),
      Some("core/src"),
    )
    assert(core.shouldScanGlobDirectory(Paths.get("")))
    assert(core.shouldScanGlobDirectory(Paths.get("core")))
    assert(core.shouldScanGlobDirectory(Paths.get("core/src")))
    assert(!core.shouldScanGlobDirectory(Paths.get("extra")))

    assertEquals(shared.globMatchers.head.prefix, None)
    assert(shared.shouldScanGlobDirectory(Paths.get("anywhere")))
  }

  test("build-target-sources-expands-globs") {
    val workspace = AbsolutePath(Files.createTempDirectory("prefixing-globs"))
    FileLayout.fromString(
      s"""|/.metals/mbt.json
          |{
          |  "namespaces": {
          |    "core": {
          |      "sources": ["core/src/**"],
          |      "scalaVersion": "${BuildInfo.scala213}"
          |    }
          |  }
          |}
          |/core/src/core/Model.scala
          |package core
          |
          |object Model
          |/core/src/core/Service.scala
          |package core
          |
          |object Service
          |/core/src/core/Helper.java
          |package core;
          |
          |class Helper {}
          |/core/src/core/ignored.conf
          |value = 1
          |""".stripMargin,
      root = workspace,
    )

    val build = MbtBuild.fromWorkspace(workspace)
    val server =
      new MbtBuildServer(workspace, () => build, ScalaVersionSelector.default)
    val targets = build.mbtTargets
    val result =
      server
        .buildTargetSources(new SourcesParams(targets.map(_.id).asJava))
        .get()
    val coreTarget = targets.find(_.name == "core").get
    val sourceItems =
      result.getItems.asScala.find(_.getTarget == coreTarget.id).get
    val sourcesByUri =
      sourceItems.getSources.asScala.map(item => item.getUri -> item).toMap

    val model = workspace.resolve("core/src/core/Model.scala").toURI.toString
    val service =
      workspace.resolve("core/src/core/Service.scala").toURI.toString
    val helper = workspace.resolve("core/src/core/Helper.java").toURI.toString
    val ignored =
      workspace.resolve("core/src/core/ignored.conf").toURI.toString

    assert(sourcesByUri.contains(model))
    assert(sourcesByUri.contains(service))
    assert(sourcesByUri.contains(helper))
    assert(!sourcesByUri.contains(ignored))
    assertEquals(sourcesByUri(model).getKind, SourceItemKind.FILE)
    assertEquals(sourcesByUri(service).getKind, SourceItemKind.FILE)
    assertEquals(sourcesByUri(helper).getKind, SourceItemKind.FILE)
  }

}

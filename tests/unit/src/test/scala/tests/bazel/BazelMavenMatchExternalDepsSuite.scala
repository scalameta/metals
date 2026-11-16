package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.HubName
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter.MavenHub
import scala.meta.io.AbsolutePath

import scribe.Level
import scribe.LogRecord
import scribe.Logger
import scribe.output.LogOutput
import scribe.output.format.OutputFormat
import scribe.writer.Writer
import tests.BaseSuite

class BazelMavenMatchExternalDepsSuite extends BaseSuite {

  private def module(id: String): MbtDependencyModule =
    MbtDependencyModule(id, s"file:///tmp/${id.replace(':', '-')}.jar", null)

  private def withTempDir[A](fn: AbsolutePath => A): A = {
    val dir = AbsolutePath(Files.createTempDirectory("bazel-maven-hub-test"))
    try fn(dir)
    finally dir.deleteRecursively()
  }

  private def writeV5Hub(
      root: AbsolutePath,
      name: String,
      coordsToVersion: Seq[(String, String)],
  ): MavenHub = {
    val entries = coordsToVersion
      .map { case (coord, version) =>
        s""""$coord": { "version": "$version" }"""
      }
      .mkString(",\n    ")
    val lock = root.resolve(name).resolve("imported_maven_install.json")
    lock.writeText(
      s"""|{
          |  "artifacts": {
          |    $entries
          |  }
          |}
          |""".stripMargin
    )
    MavenHub(HubName(name), lock)
  }

  private def captureWarnings[A](body: => A): (A, List[String]) = {
    val warnings = List.newBuilder[String]
    val writer = new Writer {
      override def write(
          record: LogRecord,
          output: LogOutput,
          outputFormat: OutputFormat,
      ): Unit =
        if (record.level.value >= Level.Warn.value) {
          warnings.synchronized {
            warnings += output.plainText
          }
        }
    }
    Logger.root
      .clearModifiers()
      .clearHandlers()
      .withHandler(writer = writer, minimumLevel = Some(Level.Warn))
      .replace()
    try {
      val result = body
      (result, warnings.result())
    } finally {
      Logger.root.reset()
    }
  }

  test("skip-internal-hubs-single-user-hub") {
    withTempDir { dir =>
      val mavenHub = writeV5Hub(
        dir,
        "maven",
        Seq(
          "org.junit.jupiter:junit-jupiter-api" -> "5.13.4",
          "com.google.guava:guava" -> "33.0.0-jre",
        ),
      )
      val modules = Seq(
        module("org.junit.jupiter:junit-jupiter-api:5.13.4"),
        module("com.google.guava:guava:33.0.0-jre"),
      )
      val externalDeps = Map(
        "//:runner" -> List(
          "@contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api",
          "@rules_jvm_external_deps//:com_google_guava_guava",
          "@rules_jvm_external//:com_google_guava_guava",
        ),
        "//:canonical-runner" -> List(
          "@@rules_jvm_external++maven+contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api",
          "@@rules_jvm_external~~maven~rules_jvm_external_deps//:com_google_guava_guava",
          "@unpinned_contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api",
        ),
        "//:app" -> List(
          "@contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api",
          "@@rules_jvm_external++maven+maven//:org_junit_jupiter_junit_jupiter_api",
        ),
      )

      val (matched, warnings) = captureWarnings {
        BazelMavenJsonImporter.matchExternalDeps(
          externalDeps,
          modules,
          List(mavenHub),
        )
      }

      assertEquals(matched("//:runner"), Nil)
      assertEquals(matched("//:canonical-runner"), Nil)
      assertEquals(
        matched("//:app"),
        List("org.junit.jupiter:junit-jupiter-api:5.13.4"),
      )
      assertEquals(warnings, Nil)
    }
  }

  test("skip-internal-hubs-multi-user-hubs-and-canonical-labels") {
    withTempDir { dir =>
      val mavenHub = writeV5Hub(
        dir,
        "maven",
        Seq("org.junit.jupiter:junit-jupiter-api" -> "5.13.4"),
      )
      val otherHub = writeV5Hub(
        dir,
        "other",
        Seq("org.junit.jupiter:junit-jupiter-api" -> "5.12.2"),
      )
      val modules = Seq(
        module("org.junit.jupiter:junit-jupiter-api:5.12.2"),
        module("org.junit.jupiter:junit-jupiter-api:5.13.4"),
      )
      val externalDeps = Map(
        "//:runner" -> List(
          "@contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api"
        ),
        "//:app" -> List(
          "@contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api",
          "@maven//:org_junit_jupiter_junit_jupiter_api",
        ),
        "//:other-test" -> List(
          "@@rules_jvm_external++maven+contrib_rules_jvm_deps//:org_junit_jupiter_junit_jupiter_api",
          "@other//:org_junit_jupiter_junit_jupiter_api",
        ),
      )

      val (matched, warnings) = captureWarnings {
        BazelMavenJsonImporter.matchExternalDeps(
          externalDeps,
          modules,
          List(mavenHub, otherHub),
        )
      }

      assertEquals(matched("//:runner"), Nil)
      assertEquals(
        matched("//:app"),
        List("org.junit.jupiter:junit-jupiter-api:5.13.4"),
      )
      assertEquals(
        matched("//:other-test"),
        List("org.junit.jupiter:junit-jupiter-api:5.12.2"),
      )
      assertEquals(warnings, Nil)
    }
  }

  test("warn-once-per-distinct-ambiguous-label") {
    val modules = Seq(
      module("org.junit.jupiter:junit-jupiter-api:5.12.2"),
      module("org.junit.jupiter:junit-jupiter-api:6.0.3"),
      module("com.google.guava:guava:32.1.3-jre"),
      module("com.google.guava:guava:33.0.0-jre"),
    )
    val sharedLabels = List(
      "@unmapped_hub//:org_junit_jupiter_junit_jupiter_api",
      "@unmapped_hub//:com_google_guava_guava",
    )
    val externalDeps = Map(
      "//:t1" -> sharedLabels,
      "//:t2" -> sharedLabels,
      "//:t3" -> sharedLabels,
    )

    val (matched, warnings) = captureWarnings {
      BazelMavenJsonImporter.matchExternalDeps(
        externalDeps,
        modules,
        Nil,
      )
    }

    val expected = List(
      "org.junit.jupiter:junit-jupiter-api:6.0.3",
      "com.google.guava:guava:33.0.0-jre",
    )
    assertEquals(matched("//:t1"), expected)
    assertEquals(matched("//:t2"), expected)
    assertEquals(matched("//:t3"), expected)
    assertEquals(warnings.size, 2)
    assert(
      warnings.exists(
        _.contains("@unmapped_hub//:org_junit_jupiter_junit_jupiter_api")
      )
    )
    assert(
      warnings.exists(_.contains("@unmapped_hub//:com_google_guava_guava"))
    )
  }
}

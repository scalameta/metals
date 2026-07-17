package tests.bazel

import scala.meta.internal.metals.mbt.importer.ScalaToolchainModules

import tests.BaseSuite

/**
 * The workspace default Scala version as rules_scala resolved it, read from
 * the generated `@…rules_scala_config` repository
 * ([[ScalaToolchainModules.scalaConfigVersion]]).
 */
class ScalaToolchainModulesSuite extends BaseSuite {

  test("scala-config-version-read-from-generated-config-repo") {
    val external = java.nio.file.Files.createTempDirectory("external")
    def configRepo(name: String, configBzl: String): Unit = {
      val dir = external.resolve(name)
      java.nio.file.Files.createDirectories(dir)
      java.nio.file.Files.write(
        dir.resolve("config.bzl"),
        configBzl.getBytes(),
      )
    }
    // bzlmod canonical naming, with the decoy `SCALA_VERSIONS=[...]` and
    // `SCALA_MAJOR_VERSION` lines the real generated file carries — the
    // line-anchored pattern must pick the single `SCALA_VERSION` default.
    configRepo(
      "rules_scala++scala_config+rules_scala_config",
      """SCALA_VERSION='2.12.21'
        |SCALA_VERSIONS=["3.1.3", "2.11.12", "2.12.21", "2.13.18"]
        |SCALA_MAJOR_VERSION='2.12'
        |""".stripMargin,
    )
    assertEquals(
      ScalaToolchainModules.scalaConfigVersion(external),
      Some("2.12.21"),
    )
  }

  test("scala-config-version-workspace-repo-naming") {
    val external = java.nio.file.Files.createTempDirectory("external")
    val dir = external.resolve("io_bazel_rules_scala_config")
    java.nio.file.Files.createDirectories(dir)
    java.nio.file.Files.write(
      dir.resolve("config.bzl"),
      "SCALA_VERSION='2.13.16'\nSCALA_VERSIONS=[\"2.13.16\"]\n".getBytes(),
    )
    // A marker FILE that also ends in the suffix must be ignored (not a dir).
    java.nio.file.Files.write(
      external.resolve("@io_bazel_rules_scala_config.marker"),
      Array.emptyByteArray,
    )
    assertEquals(
      ScalaToolchainModules.scalaConfigVersion(external),
      Some("2.13.16"),
    )
  }

  test("scala-config-version-absent-without-rules-scala") {
    val external = java.nio.file.Files.createTempDirectory("external")
    java.nio.file.Files.createDirectories(external.resolve("+maven+unrelated"))
    assertEquals(ScalaToolchainModules.scalaConfigVersion(external), None)
  }

  test("scala-config-version-picks-highest-across-stale-repos") {
    val external = java.nio.file.Files.createTempDirectory("external")
    def configRepo(name: String, version: String): Unit = {
      val dir = external.resolve(name)
      java.nio.file.Files.createDirectories(dir)
      java.nio.file.Files.write(
        dir.resolve("config.bzl"),
        s"SCALA_VERSION='$version'\n".getBytes(),
      )
    }
    // Patch versions are chosen so lexicographic order ("2.13.10" first)
    // disagrees with SemVer order (2.13.12 is newer): the result must be the
    // SemVer-highest, not the lexicographically smallest.
    configRepo("rules_scala~6.5.0~scala_config~rules_scala_config", "2.13.10")
    configRepo("rules_scala~6.6.0~scala_config~rules_scala_config", "2.13.12")
    assertEquals(
      ScalaToolchainModules.scalaConfigVersion(external),
      Some("2.13.12"),
    )
  }
}

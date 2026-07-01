package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.io.AbsolutePath

import tests.BaseSuite

/**
 * [[BazelMavenJsonImporter.extractRepositoryNameFromBazelConfig]] — the Maven
 * hub repository name parsed from `MODULE.bazel` / `WORKSPACE`. The headline
 * regression this guards against is latching onto an unrelated `name = "..."`
 * (e.g. detecting `scalapb` as the Maven repo) because the `maven_install`
 * match accidentally included a `pinned_`/`unpinned_` helper.
 */
class BazelMavenRepoNameSuite extends BaseSuite {

  private def repoName(files: (String, String)*): String = {
    val dir = AbsolutePath(Files.createTempDirectory("maven-repo-name"))
    files.foreach { case (name, content) =>
      val path = dir.resolve(name).toNIO
      Files.createDirectories(path.getParent)
      Files.writeString(path, content)
    }
    BazelMavenJsonImporter.extractRepositoryNameFromBazelConfig(dir)
  }

  test("defaults-to-maven-with-no-config-files") {
    assertEquals(repoName(), "maven")
  }

  test("named-bzlmod-maven-install") {
    val module =
      """|bazel_dep(name = "rules_jvm_external", version = "6.2")
         |maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
         |maven.install(
         |    name = "my_maven",
         |    artifacts = ["com.google.guava:guava:33.0.0-jre"],
         |)
         |use_repo(maven, "my_maven")
         |""".stripMargin
    assertEquals(repoName("MODULE.bazel" -> module), "my_maven")
  }

  test("named-workspace-maven_install") {
    val workspace =
      """|load("@rules_jvm_external//:defs.bzl", "maven_install")
         |maven_install(
         |    name = "custom_repo",
         |    artifacts = ["junit:junit:4.13.2"],
         |)
         |""".stripMargin
    assertEquals(repoName("WORKSPACE" -> workspace), "custom_repo")
  }

  test("ignores-pinned-and-unpinned-helpers") {
    // `pinned_`/`unpinned_maven_install` are rules_jvm_external boilerplate that
    // take no `name` and reference the already-defined `@maven` repo. The match
    // must not run forward into the next unrelated `name = "..."`.
    val workspace =
      """|load("@rules_jvm_external//:defs.bzl", "pinned_maven_install")
         |pinned_maven_install()
         |scala_repositories(name = "scalapb")
         |""".stripMargin
    assertEquals(repoName("WORKSPACE" -> workspace), "maven")
  }

  test("first-of-multiple-installs-wins") {
    val module =
      """|maven = use_extension("@rules_jvm_external//:extensions.bzl", "maven")
         |maven.install(name = "primary", artifacts = [])
         |maven.install(name = "secondary", artifacts = [])
         |""".stripMargin
    assertEquals(repoName("MODULE.bazel" -> module), "primary")
  }

  test("repo-name-from-included-file") {
    val module = """include("//:maven_deps.bzl")"""
    val included = """maven.install(name = "included_maven", artifacts = [])"""
    assertEquals(
      repoName(
        "MODULE.bazel" -> module,
        "maven_deps.bzl" -> included,
      ),
      "included_maven",
    )
  }
}

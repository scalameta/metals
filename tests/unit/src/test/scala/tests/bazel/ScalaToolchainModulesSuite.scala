package tests.bazel

import scala.jdk.CollectionConverters._

import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelBuildSrcs.InactiveSource
import scala.meta.internal.metals.mbt.importer.BazelMbtBuildSupport
import scala.meta.internal.metals.mbt.importer.BazelMbtNamespaceMode
import scala.meta.internal.metals.mbt.importer.ScalaToolchainModules

import tests.BaseSuite

/**
 * Version-resolved Scala toolchain modules for Bazel MBT namespaces:
 * [[ScalaToolchainModules.Resolution.moduleIdsFor]] selection/dedup rules and
 * their application per namespace in [[BazelMbtBuildSupport.fromDiscovery]].
 */
class ScalaToolchainModulesSuite extends BaseSuite {

  private val library2 = "org.scala-lang:scala-library:2.12.21"
  private val library3 = "org.scala-lang:scala3-library_3:3.8.3"
  private val library213 = "org.scala-lang:scala-library:2.13.16"
  private val compiler2 = "org.scala-lang:scala-compiler:2.12.21"
  private val reflect2 = "org.scala-lang:scala-reflect:2.12.21"

  private def module(id: String): MbtDependencyModule =
    MbtDependencyModule(id, s"file:///jars/$id.jar", null)

  private val toolchain = ScalaToolchainModules.Resolution(
    modules =
      List(library2, library3, library213, compiler2, reflect2).map(module),
    libraryIdsByVersion = Map(
      "2.12.21" -> List(library2),
      "3.8.3" -> List(library3, library213),
    ),
    compilerIdsByVersion = Map(
      "2.12.21" -> List(compiler2, reflect2, library2)
    ),
    compilerClasspathTargets = Set("//pkg/a:plugin"),
  )

  test("library-bundle-for-plain-target") {
    assertEquals(
      toolchain.moduleIdsFor(
        Some("2.12.21"),
        List("//pkg/b:lib"),
        existingIds = Set.empty,
      ),
      Set(library2),
    )
  }

  test("compiler-bundle-for-compiler-classpath-target-self-deduped") {
    // scala-library appears in both bundles — once.
    assertEquals(
      toolchain.moduleIdsFor(
        Some("2.12.21"),
        List("//pkg/a:plugin"),
        existingIds = Set.empty,
      ),
      Set(library2, compiler2, reflect2),
    )
  }

  test("existing-maven-module-suppresses-same-artifact") {
    // A scala-library already pinned through @maven// (any version) wins.
    assertEquals(
      toolchain.moduleIdsFor(
        Some("2.12.21"),
        List("//pkg/b:lib"),
        existingIds = Set("org.scala-lang:scala-library:2.12.18"),
      ),
      Set.empty[String],
    )
  }

  test("no-scala-version-no-modules") {
    assertEquals(
      toolchain.moduleIdsFor(None, List("//pkg/a:plugin"), Set.empty),
      Set.empty[String],
    )
  }

  test("testing-bundle-for-scala-test-target-only") {
    val scalatest = "org.scalatest:scalatest_2.12:3.2.19"
    val withTesting = toolchain.copy(
      testingIdsByVersion = Map("2.12.21" -> List(scalatest)),
      testTargets = Set("//pkg/t:some_test"),
    )
    assertEquals(
      withTesting.moduleIdsFor(
        Some("2.12.21"),
        List("//pkg/t:some_test"),
        existingIds = Set.empty,
      ),
      Set(library2, scalatest),
    )
    assertEquals(
      withTesting.moduleIdsFor(
        Some("2.12.21"),
        List("//pkg/b:lib"),
        existingIds = Set.empty,
      ),
      Set(library2),
    )
  }

  test("testing-modules-discovered-from-bazel-external-dir") {
    val external = java.nio.file.Files.createTempDirectory("external")
    def repo(name: String, jars: String*): Unit = {
      val dir = external.resolve(name)
      java.nio.file.Files.createDirectories(dir)
      jars.foreach(jar => java.nio.file.Files.createFile(dir.resolve(jar)))
    }
    repo(
      "+scala_deps+io_bazel_rules_scala_scalatest_2_12_21",
      "scalatest_2.12-3.2.19.jar",
      "scalatest_2.12-3.2.19-src.jar",
    )
    repo(
      "+scala_deps+io_bazel_rules_scala_scalactic_2_12_21",
      "scalactic_2.12-3.2.19.jar",
    )
    repo("+maven+unrelated", "bar-1.0.jar")

    val byVersion = ScalaToolchainModules.testingModules(external)
    assertEquals(byVersion.keySet, Set("2.12.21"))
    val modules = byVersion("2.12.21")
    assertEquals(
      modules.map(_.id),
      List(
        "org.scalactic:scalactic_2.12:3.2.19",
        "org.scalatest:scalatest_2.12:3.2.19",
      ),
    )
    val scalatestModule =
      modules.find(_.id.startsWith("org.scalatest")).get
    assert(scalatestModule.jar.endsWith("scalatest_2.12-3.2.19.jar"))
    assert(
      scalatestModule.sources.endsWith("scalatest_2.12-3.2.19-src.jar"),
      "sources jar from the external repo should be attached",
    )
    val scalacticModule =
      modules.find(_.id.startsWith("org.scalactic")).get
    assertEquals(
      Option(scalacticModule.sources),
      None,
      "no -src.jar present -> no sources",
    )
  }

  test("testing-toolchain-versions-detects-repos-without-materialized-jars") {
    val external = java.nio.file.Files.createTempDirectory("external")
    def repo(name: String, jars: String*): Unit = {
      val dir = external.resolve(name)
      java.nio.file.Files.createDirectories(dir)
      jars.foreach(jar => java.nio.file.Files.createFile(dir.resolve(jar)))
    }
    // 2.12.21 toolchain repo with its jar materialized.
    repo(
      "+scala_deps+io_bazel_rules_scala_scalatest_core_2_12_21",
      "scalatest-core_2.12-3.2.19.jar",
    )
    // 2.13.18 toolchain repo present but jars not fetched yet.
    repo("+scala_deps+io_bazel_rules_scala_scalatest_core_2_13_18")
    repo("+maven+unrelated", "bar-1.0.jar")

    // The discriminator sees both versions (repos exist) ...
    assertEquals(
      ScalaToolchainModules.testingToolchainVersions(external),
      Set("2.12.21", "2.13.18"),
    )
    // ... while the module scan only yields the one whose jar is materialized,
    // so the caller can tell "in use but not built" from "not in use".
    assertEquals(
      ScalaToolchainModules.testingModules(external).keySet,
      Set("2.12.21"),
    )
  }

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
    // A stale config repo left behind by a rules_scala upgrade coexists with
    // the current one (Bazel does not GC the old external/<…> directory). The
    // two patch versions are chosen so lexicographic order ("2.13.10" first)
    // disagrees with semantic order (2.13.12 is newer): the result must be the
    // highest by SemVer, not the lexicographically smallest.
    configRepo("rules_scala~6.5.0~scala_config~rules_scala_config", "2.13.10")
    configRepo("rules_scala~6.6.0~scala_config~rules_scala_config", "2.13.12")
    assertEquals(
      ScalaToolchainModules.scalaConfigVersion(external),
      Some("2.13.12"),
    )
  }

  test("from-discovery-applies-version-matching-bundles") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/a:plugin"),
      srcsByTarget = Map(
        "//pkg/a:plugin" -> List("//pkg/a:A2.scala", "//pkg/a:A3.scala")
      ),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map.empty,
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      scalaVersionByTarget = Map("//pkg/a:plugin" -> Some("2.12.21")),
      inactiveSources =
        Map("//pkg/a:A3.scala" -> InactiveSource("3.8.3", "//pkg/a:plugin")),
      versionSpecificSourceLabels = Set("//pkg/a:A2.scala", "//pkg/a:A3.scala"),
      toolchain = toolchain,
    )
    val namespaces = build.getNamespaces.asScala

    // The Scala 2 origin namespace: its own version's bundles, compiler
    // included because the target depends on scala_compile_classpath.
    assertEquals(
      namespaces("//pkg/a").getDependencyModuleIds.asScala.toSet,
      Set(library2, compiler2, reflect2),
    )
    // The inactive Scala 3 namespace: the BRANCH version's library bundle,
    // not the origin's 2.12 jars; no 3.x compiler bundle was resolved.
    assertEquals(
      namespaces("//pkg/a@3.8.3").getDependencyModuleIds.asScala.toSet,
      Set(library3, library213),
    )
    // Top-level module list carries exactly the referenced toolchain modules.
    assertEquals(
      build.getDependencyModules().asScala.map(_.id).toSet,
      Set(library2, compiler2, reflect2, library3, library213),
    )
  }

  test("from-discovery-java-only-namespace-gets-no-toolchain-modules") {
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/j:lib"),
      srcsByTarget = Map("//pkg/j:lib" -> List("//pkg/j:Parser.java")),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map.empty,
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = Nil,
      // The project-wide fallback version applies even to java_library
      // targets — it must not pull the Scala stdlib onto their classpath.
      scalaVersionByTarget = Map("//pkg/j:lib" -> Some("2.12.21")),
      inactiveSources = Map.empty,
      versionSpecificSourceLabels = Set.empty,
      toolchain = toolchain,
    )
    val namespaces = build.getNamespaces.asScala
    assertEquals(
      namespaces("//pkg/j").getDependencyModuleIds.asScala.toList,
      List.empty[String],
    )
    assertEquals(
      build.getDependencyModules().asScala.map(_.id).toList,
      List.empty[String],
    )
  }

  test("from-discovery-suppressed-toolchain-modules-stay-out-of-the-build") {
    val mavenLibrary =
      MbtDependencyModule(
        "org.scala-lang:scala-library:2.12.18",
        "file:///maven/scala-library-2.12.18.jar",
        null,
      )
    val build = BazelMbtBuildSupport.fromDiscovery(
      granularity = BazelMbtNamespaceMode.BuildFile,
      targetLabels = List("//pkg/b:lib"),
      srcsByTarget = Map("//pkg/b:lib" -> List("//pkg/b:B.scala")),
      scalacOptionsByTarget = Map.empty,
      javacOptionsByTarget = Map.empty,
      directDepRules = Map.empty,
      externalDepsByTarget = Map("//pkg/b:lib" -> List(mavenLibrary.id)),
      runTargets = Set.empty,
      classDirectoriesByTarget = Map.empty,
      dependencyModules = List(mavenLibrary),
      scalaVersionByTarget = Map("//pkg/b:lib" -> Some("2.12.21")),
      inactiveSources = Map.empty,
      versionSpecificSourceLabels = Set.empty,
      toolchain = toolchain,
    )
    val namespaces = build.getNamespaces.asScala
    // The @maven//-pinned stdlib wins; the toolchain library is suppressed
    // both on the namespace and in the build's module list.
    assertEquals(
      namespaces("//pkg/b").getDependencyModuleIds.asScala.toList,
      List(mavenLibrary.id),
    )
    assertEquals(
      build.getDependencyModules().asScala.map(_.id).toList,
      List(mavenLibrary.id),
    )
  }
}

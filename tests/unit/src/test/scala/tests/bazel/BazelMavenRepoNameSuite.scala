package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.mbt.MbtDependencyModule
import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.io.AbsolutePath

import tests.BaseSuite

/**
 * [[BazelMavenJsonImporter.discoverMavenHubs]] — the rules_jvm_external
 * Maven hub repository name(s), read from the materialized Bazel `external/`
 * directory rather than parsed out of `MODULE.bazel` / `WORKSPACE` with a regex.
 * A pinned hub is identified by the `imported_maven_install.json` marker; the
 * repo name is the directory name (WORKSPACE mode) or the trailing `+`/`~`
 * segment of a bzlmod canonical name.
 */
class BazelMavenRepoNameSuite extends BaseSuite {

  /** Build an `external/` dir; each (dirName -> isHub) creates a subdirectory, hubs get the marker. */
  private def externalDir(hubs: (String, Boolean)*): AbsolutePath = {
    val ext = AbsolutePath(Files.createTempDirectory("external"))
    hubs.foreach { case (name, isHub) =>
      val dir = ext.resolve(name).toNIO
      Files.createDirectories(dir)
      if (isHub)
        Files.writeString(dir.resolve("imported_maven_install.json"), "{}")
    }
    ext
  }

  /**
   * Build an `external/` dir whose hub subdirs each carry an
   * `imported_maven_install.json` with the given `coordinate -> version`
   * artifacts, so [[BazelMavenJsonImporter.hubCoordinateVersions]] can read them.
   */
  private def externalDirWithVersions(
      hubs: (String, Map[String, String])*
  ): AbsolutePath = {
    val ext = AbsolutePath(Files.createTempDirectory("external"))
    hubs.foreach { case (name, coordToVersion) =>
      val dir = ext.resolve(name).toNIO
      Files.createDirectories(dir)
      val artifacts = coordToVersion
        .map { case (coord, version) =>
          s""""$coord": {"version": "$version"}"""
        }
        .mkString(",")
      Files.writeString(
        dir.resolve("imported_maven_install.json"),
        s"""{"artifacts": {$artifacts}}""",
      )
    }
    ext
  }

  private def module(id: String): MbtDependencyModule =
    MbtDependencyModule(id = id, jar = s"file:///$id.jar", sources = null)

  private def moduleWithSources(id: String): MbtDependencyModule =
    MbtDependencyModule(
      id = id,
      jar = s"file:///$id.jar",
      sources = s"file:///$id-sources.jar",
    )

  test("sanitize-coordinate") {
    assertEquals(
      BazelMavenJsonImporter.sanitizeCoordinate("com.google.guava", "guava"),
      "com_google_guava_guava",
    )
    assertEquals(
      BazelMavenJsonImporter.sanitizeCoordinate("org.scalameta", "trees_2.13"),
      "org_scalameta_trees_2_13",
    )
  }

  test("label-coordinate-and-hub") {
    assertEquals(
      BazelMavenJsonImporter
        .labelCoordinate("@maven//:com_google_guava_guava"),
      Some("com_google_guava_guava"),
    )
    assertEquals(
      BazelMavenJsonImporter.labelHubName("@maven//:com_google_guava_guava"),
      Some(BazelMavenJsonImporter.HubName("maven")),
    )
    assertEquals(
      BazelMavenJsonImporter.labelCoordinate(
        "@@rules_jvm_external++maven+maven//:org_scalameta_trees_2_13"
      ),
      Some("org_scalameta_trees_2_13"),
    )
    assertEquals(
      BazelMavenJsonImporter.labelHubName(
        "@@rules_jvm_external++maven+maven//:org_scalameta_trees_2_13"
      ),
      Some(BazelMavenJsonImporter.HubName("maven")),
    )
    assertEquals(
      BazelMavenJsonImporter.labelCoordinate("//:some_target"),
      Some("some_target"),
    )
    assertEquals(
      BazelMavenJsonImporter.labelCoordinate("@maven//foo:bar"),
      None,
    )
  }

  test("match-single-hub-by-suffix") {
    val ext =
      externalDirWithVersions(
        "maven" -> Map("com.google.guava:guava" -> "31.0")
      )
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(ext))
    val guava = module("com.google.guava:guava:31.0")
    val result = BazelMavenJsonImporter.matchExternalDeps(
      Map("//app" -> List("@maven//:com_google_guava_guava")),
      Seq(guava),
      hubs,
    )
    assertEquals(result, Map("//app" -> List("com.google.guava:guava:31.0")))
  }

  test("match-unknown-hub-single-candidate-resolves") {
    // When only one module carries the coordinate suffix, the hub name is
    // never consulted (the `sizeIs <= 1`
    // fast path), so a label whose `@<hub>` is not among the discovered hubs —
    // a bzlmod `use_repo` alias, or a hub whose lock could not be read — still
    // resolves rather than matching nothing.
    val ext =
      externalDirWithVersions(
        "maven" -> Map("com.google.guava:guava" -> "31.0")
      )
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(ext))
    val guava = module("com.google.guava:guava:31.0")
    val result = BazelMavenJsonImporter.matchExternalDeps(
      Map("//app" -> List("@unknown_hub//:com_google_guava_guava")),
      Seq(guava),
      hubs,
    )
    assertEquals(result, Map("//app" -> List("com.google.guava:guava:31.0")))
  }

  test("match-skips-unknown-and-non-coordinate-labels") {
    val ext =
      externalDirWithVersions(
        "maven" -> Map("com.google.guava:guava" -> "31.0")
      )
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(ext))
    val guava = module("com.google.guava:guava:31.0")
    val result = BazelMavenJsonImporter.matchExternalDeps(
      Map(
        "//app" -> List(
          "@maven//:com_google_guava_guava", // pinned -> resolves
          "@maven//:io_absent_absent", // not pinned -> dropped
          "@maven//pkg:target", // not a `//:` dep label -> dropped
        )
      ),
      Seq(guava),
      hubs,
    )
    assertEquals(result, Map("//app" -> List("com.google.guava:guava:31.0")))
  }

  test("match-multi-hub-with-skew-disambiguates-by-hub") {
    val ext = externalDirWithVersions(
      "hub_a" -> Map("g:a" -> "1.0"),
      "hub_b" -> Map("g:a" -> "2.0"),
    )
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(ext))
    val modules = Seq(module("g:a:1.0"), module("g:a:2.0"))
    val result = BazelMavenJsonImporter.matchExternalDeps(
      Map(
        "//a" -> List("@hub_a//:g_a"),
        "//b" -> List("@hub_b//:g_a"),
      ),
      modules,
      hubs,
    )
    assertEquals(result("//a"), List("g:a:1.0"))
    assertEquals(result("//b"), List("g:a:2.0"))
  }

  test("match-tiebreaker-fallback-picks-highest-semantic-version") {
    // >1 candidate and a hub that does not disambiguate (unknown hub) => the
    // fallback must pick the highest SEMANTIC version. 2.13.0 > 2.9.0
    // semantically, but sorts BEFORE 2.9.0 lexicographically ('1' < '9'), so a
    // lexical max would wrongly pick 2.9.0.
    val ext = externalDirWithVersions("maven" -> Map("g:a" -> "2.9.0"))
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(ext))
    val modules = Seq(module("g:a:2.9.0"), module("g:a:2.13.0"))
    val result = BazelMavenJsonImporter.matchExternalDeps(
      Map("//t" -> List("@unknown_hub//:g_a")),
      modules,
      hubs,
    )
    assertEquals(result("//t"), List("g:a:2.13.0"))
  }

  test("dedup-modules-prefers-the-variant-with-sources") {
    // The same id resolved from two locks — keep the one that carries a
    // sources jar; output sorted by id.
    val deduped = BazelMavenJsonImporter.dedupModulesById(
      Seq(module("g:a:1.0"), module("g:b:2.0"), moduleWithSources("g:a:1.0"))
    )
    assertEquals(deduped.map(_.id), List("g:a:1.0", "g:b:2.0"))
    assert(deduped.find(_.id == "g:a:1.0").exists(_.sourcesURI.isDefined))
  }

  test("lock-files-prefer-hub-locks-over-checked-in-lock") {
    // When hubs are materialized, read their imported_maven_install.json,
    // not a checked-in project maven_install.json.
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(
      Seq(externalDir("maven" -> true))
    )
    val projectDir = AbsolutePath(Files.createTempDirectory("project"))
    Files.writeString(projectDir.resolve("maven_install.json").toNIO, "{}")
    assertEquals(
      BazelMavenJsonImporter.lockFilesFor(projectDir, hubs),
      hubs.map(_.importedLock),
    )
  }

  test("lock-files-fall-back-to-checked-in-lock-without-hubs") {
    // With no materialized hub, the heuristic project-tree search finds the
    // checked-in maven_install.json.
    val projectDir = AbsolutePath(Files.createTempDirectory("project"))
    val lock = projectDir.resolve("maven_install.json")
    Files.writeString(lock.toNIO, "{}")
    assertEquals(
      BazelMavenJsonImporter.lockFilesFor(projectDir, Nil),
      Seq(lock),
    )
  }

  test("discover-hubs-returns-names-and-lock-paths") {
    val ext = externalDir(
      "maven" -> true,
      "rules_jvm_external_deps" -> true,
      "rules_jvm_external" -> false,
    )
    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(ext))
    assertEquals(hubs.map(_.name.value), List("maven"))
    assert(Files.exists(hubs.head.importedLock.toNIO))
    assertEquals(
      hubs.head.importedLock.toNIO.getFileName.toString,
      "imported_maven_install.json",
    )
  }

  test("hub-coordinate-versions-new-format") {
    val dir = AbsolutePath(Files.createTempDirectory("hub-new"))
    val lock = dir.resolve("imported_maven_install.json")
    Files.writeString(
      lock.toNIO,
      """{"artifacts": {"com.google.guava:guava": {"version": "31.0"}}}""",
    )
    assertEquals(
      BazelMavenJsonImporter.hubCoordinateVersions(
        BazelMavenJsonImporter.MavenHub(
          BazelMavenJsonImporter.HubName("maven"),
          lock,
        )
      ),
      Map("com_google_guava_guava" -> "31.0"),
    )
  }

  test("hub-coordinate-versions-legacy-format") {
    // rules_jvm_external v4 and earlier: dependency_tree.dependencies[].coord.
    // Sources entries (`:jar:sources:`) must be skipped.
    val dir = AbsolutePath(Files.createTempDirectory("hub-legacy"))
    val lock = dir.resolve("imported_maven_install.json")
    Files.writeString(
      lock.toNIO,
      """|{"dependency_tree": {"dependencies": [
         |  {"coord": "org.scalaz:scalaz-core_2.12:7.2.20"},
         |  {"coord": "org.scalaz:scalaz-core_2.12:jar:sources:7.2.20"},
         |  {"coord": "com.lmax:disruptor:3.4.2"}
         |]}}""".stripMargin,
    )
    assertEquals(
      BazelMavenJsonImporter.hubCoordinateVersions(
        BazelMavenJsonImporter.MavenHub(
          BazelMavenJsonImporter.HubName("maven_custom_name"),
          lock,
        )
      ),
      Map(
        "org_scalaz_scalaz_core_2_12" -> "7.2.20",
        "com_lmax_disruptor" -> "3.4.2",
      ),
    )
  }

  test("discover-no-hubs-when-external-empty") {
    assert(BazelMavenJsonImporter.discoverMavenHubs(Seq(externalDir())).isEmpty)
  }

  test("discover-workspace-hub-by-marker") {
    // The default WORKSPACE-mode layout: a `maven` hub plus the empty
    // `unpinned_maven` and the ruleset dir without the marker.
    val ext = externalDir(
      "maven" -> true,
      "unpinned_maven" -> false,
      "rules_jvm_external" -> false,
    )
    assertEquals(
      BazelMavenJsonImporter.discoverMavenHubs(Seq(ext)).map(_.name.value),
      List("maven"),
    )
  }

  test("discover-strips-unpinned-prefix") {
    // A pinned `unpinned_<name>` hub (the materialized form of an unpinned
    // WORKSPACE install) reports the stripped apparent name `<name>`.
    val ext = externalDir("unpinned_maven" -> true)
    assertEquals(
      BazelMavenJsonImporter.discoverMavenHubs(Seq(ext)).map(_.name.value),
      List("maven"),
    )
  }

  test("discover-custom-workspace-hub-name") {
    val ext = externalDir("custom_repo" -> true)
    assertEquals(
      BazelMavenJsonImporter.discoverMavenHubs(Seq(ext)).map(_.name.value),
      List("custom_repo"),
    )
  }

  test("discover-bzlmod-canonical-name-trailing-segment") {
    // Bazel 8 (`++`) and Bazel 7 (`~`) canonical names -> trailing segment.
    val bazel8 = externalDir("rules_jvm_external++maven+maven" -> true)
    assertEquals(
      BazelMavenJsonImporter.discoverMavenHubs(Seq(bazel8)).map(_.name.value),
      List("maven"),
    )
    val bazel7 = externalDir("rules_jvm_external~6.2~maven~maven" -> true)
    assertEquals(
      BazelMavenJsonImporter.discoverMavenHubs(Seq(bazel7)).map(_.name.value),
      List("maven"),
    )
  }

  test("discover-bzlmod-custom-hub-name") {
    // The hub is NOT named `maven` but `maven.install(name = "custom_maven",
    // ...)`, so the bzlmod canonical dir is
    // `rules_jvm_external++maven+custom_maven` and the apparent name is the
    // trailing segment `custom_maven` — read from the directory, never parsed
    // out of `MODULE.bazel`.
    val ext = externalDir("rules_jvm_external++maven+custom_maven" -> true)
    assertEquals(
      BazelMavenJsonImporter.discoverMavenHubs(Seq(ext)).map(_.name.value),
      List("custom_maven"),
    )
  }

  test("discover-returns-all-hubs-among-multiple") {
    val ext = externalDir("custom_repo" -> true, "maven" -> true)
    assertEquals(
      BazelMavenJsonImporter
        .discoverMavenHubs(Seq(ext))
        .map(_.name.value)
        .sorted,
      List("custom_repo", "maven"),
    )
  }
}

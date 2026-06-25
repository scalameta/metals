package tests.bazel

import java.nio.file.Files

import scala.meta.internal.metals.mbt.importer.BazelMavenJsonImporter
import scala.meta.io.AbsolutePath

import tests.BaseSuite

/**
 * In-memory coverage of [[BazelMavenJsonImporter.importMaven]]'s JAR location —
 * the `findJarPath` / `findBzlmodJar` machinery — against a materialized
 * `external/` tree built from temp directories with empty placeholder JARs (only
 * the file's existence is checked). Exercises the two on-disk layouts a
 * non-default hub produces, plus the legacy v4 extraction path end to end.
 *
 * Synthetic `org.example.*` coordinates are used so the `~/.m2/repository`
 * fallbacks in `findJarPath` / `findSourcesPath` can never accidentally resolve
 * a real cached artifact and make the assertions machine-dependent.
 */
class BazelMavenImportSuite extends BaseSuite {

  /** Create `path` (and its parents) as an empty placeholder file. */
  private def touch(path: AbsolutePath): AbsolutePath = {
    Files.createDirectories(path.toNIO.getParent)
    if (!Files.exists(path.toNIO)) Files.createFile(path.toNIO)
    path
  }

  /** Write a hub's `imported_maven_install.json` lock, creating the hub dir. */
  private def writeLock(hubDir: AbsolutePath, content: String): Unit = {
    Files.createDirectories(hubDir.toNIO)
    Files.writeString(
      hubDir.resolve("imported_maven_install.json").toNIO,
      content,
    )
  }

  /** A fresh output base whose `external/` directory the test populates. */
  private def freshOutputBase(): AbsolutePath =
    AbsolutePath(Files.createTempDirectory("output-base"))

  private def projectDir(): AbsolutePath =
    AbsolutePath(Files.createTempDirectory("project"))

  /**
   * Resolve a stored `jar`/`sources` string to a filesystem path. Every
   * resolver — the v5+ path and the legacy `file`-relative resolver
   * (`findJarFromLegacyPath`) alike — stores a `file:` URI, which is the
   * contract of `MbtDependencyModule.jar`/`sources`, so this asserts the URI
   * shape and rejects a bare filesystem path.
   */
  private def resolvedPath(uri: String): java.nio.file.Path = {
    assert(
      uri.startsWith("file:"),
      s"expected a file: URI per the MbtDependencyModule contract, got: $uri",
    )
    java.nio.file.Paths.get(java.net.URI.create(uri))
  }

  test("import-resolves-workspace-jar-for-custom-hub") {
    // A WORKSPACE-mode hub that is NOT named `maven`. The JAR lives under
    // `external/<hub>/v1/https/...`, so `findJarPath` resolves it by templating
    // the hub name into its workspace patterns. The lock is the v5+ `artifacts`
    // format.
    val outputBase = freshOutputBase()
    val external = outputBase.resolve("external")
    val hub = external.resolve("custom_maven")
    writeLock(
      hub,
      """{"artifacts": {"org.example.custom:lib": {"version": "1.0"}}}""",
    )
    val jar = touch(
      hub.resolve(
        "v1/https/repo1.maven.org/maven2/org/example/custom/lib/1.0/lib-1.0.jar"
      )
    )

    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(external))
    assertEquals(hubs.map(_.name.value), List("custom_maven"))

    val modules =
      BazelMavenJsonImporter.importMaven(
        projectDir(),
        Some(outputBase.toNIO),
        hubs,
      )
    assertEquals(modules.map(_.id), Seq("org.example.custom:lib:1.0"))
    assert(
      Files.isSameFile(resolvedPath(modules.head.jar), jar.toNIO),
      s"jar did not resolve to the materialized workspace JAR: ${modules.head.jar}",
    )
    assert(modules.head.sourcesURI.isEmpty)
  }

  test("import-resolves-bzlmod-jar-for-custom-hub") {
    // bzlmod: the same custom hub, but the JAR sits in a per-artifact bzlmod
    // repo `rules_jvm_external++maven+<artifactDir>` alongside the hub repo
    // `rules_jvm_external++maven+custom_maven`. This drives `findBzlmodJar` (the
    // Bazel 8 `++maven+` branch).
    val outputBase = freshOutputBase()
    val external = outputBase.resolve("external")
    writeLock(
      external.resolve("rules_jvm_external++maven+custom_maven"),
      """{"artifacts": {"org.example.second:lib": {"version": "2.0"}}}""",
    )
    val jar = touch(
      external
        .resolve("rules_jvm_external++maven+org_example_second_lib_2_0")
        .resolve("file/v1/org/example/second/lib/2.0/lib-2.0.jar")
    )

    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(external))
    assertEquals(hubs.map(_.name.value), List("custom_maven"))

    val modules =
      BazelMavenJsonImporter.importMaven(
        projectDir(),
        Some(outputBase.toNIO),
        hubs,
      )
    assertEquals(modules.map(_.id), Seq("org.example.second:lib:2.0"))
    assert(
      Files.isSameFile(resolvedPath(modules.head.jar), jar.toNIO),
      s"jar did not resolve to the materialized bzlmod JAR: ${modules.head.jar}",
    )
  }

  test("import-extracts-modules-from-legacy-v4-lock") {
    // Drive the full legacy v4 `dependency_tree` extraction path through
    // `importMaven`. Covers `extractFromLegacyFormat` / `parseLegacyDependency`
    // / `findJarFromLegacyPath` — none of which the synthetic
    // `hubCoordinateVersions` tests reach — including:
    //   - the `file`-relative JAR location,
    //   - pairing a `:jar:sources:` sources entry to its jar,
    //   - leaving sources null when no sources entry exists,
    //   - skipping a SNAPSHOT coordinate entirely.
    val outputBase = freshOutputBase()
    val external = outputBase.resolve("external")
    val hub = external.resolve("maven")
    writeLock(
      hub,
      """|{"dependency_tree": {"dependencies": [
         |  {"coord": "org.example.legacy:widget:1.2.3",
         |   "file": "v1/https/repo1.maven.org/maven2/org/example/legacy/widget/1.2.3/widget-1.2.3.jar"},
         |  {"coord": "org.example.legacy:widget:jar:sources:1.2.3",
         |   "file": "v1/https/repo1.maven.org/maven2/org/example/legacy/widget/1.2.3/widget-1.2.3-sources.jar"},
         |  {"coord": "org.example.legacy:gadget:4.5.6",
         |   "file": "v1/https/repo1.maven.org/maven2/org/example/legacy/gadget/4.5.6/gadget-4.5.6.jar"},
         |  {"coord": "org.example.legacy:snap:9.9.9-SNAPSHOT",
         |   "file": "v1/https/repo1.maven.org/maven2/org/example/legacy/snap/9.9.9-SNAPSHOT/snap-9.9.9-SNAPSHOT.jar"}
         |]}}""".stripMargin,
    )
    val v1 = hub.resolve("v1/https/repo1.maven.org/maven2/org/example/legacy")
    val widgetJar =
      touch(v1.resolve("widget/1.2.3/widget-1.2.3.jar"))
    val widgetSources =
      touch(v1.resolve("widget/1.2.3/widget-1.2.3-sources.jar"))
    val gadgetJar =
      touch(v1.resolve("gadget/4.5.6/gadget-4.5.6.jar"))
    // No JAR materialized for the SNAPSHOT: it must be dropped before any lookup.

    val hubs = BazelMavenJsonImporter.discoverMavenHubs(Seq(external))
    val modules =
      BazelMavenJsonImporter.importMaven(
        projectDir(),
        Some(outputBase.toNIO),
        hubs,
      )

    // SNAPSHOT dropped; output sorted by id (gadget < widget).
    assertEquals(
      modules.map(_.id),
      Seq("org.example.legacy:gadget:4.5.6", "org.example.legacy:widget:1.2.3"),
    )
    val byId = modules.map(m => m.id -> m).toMap
    val widget = byId("org.example.legacy:widget:1.2.3")
    val gadget = byId("org.example.legacy:gadget:4.5.6")
    assert(Files.isSameFile(resolvedPath(widget.jar), widgetJar.toNIO))
    assert(
      Option(widget.sources)
        .exists(s => Files.isSameFile(resolvedPath(s), widgetSources.toNIO)),
      s"widget sources did not pair to the materialized sources JAR: ${widget.sources}",
    )
    assert(Files.isSameFile(resolvedPath(gadget.jar), gadgetJar.toNIO))
    assert(
      gadget.sources == null,
      s"gadget should have no sources: ${gadget.sources}",
    )
  }
}

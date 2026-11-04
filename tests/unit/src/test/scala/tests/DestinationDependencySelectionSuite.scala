package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.DestinationProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.TargetData
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.IndexingResult
import scala.meta.internal.mtags.Mtags
import scala.meta.internal.mtags.OnDemandSymbolIndex
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.BuildTarget
import ch.epfl.scala.bsp4j.BuildTargetCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.WorkspaceBuildTargetsResult

class DestinationDependencySelectionSuite extends BaseTablesSuite {
  for (olderFirst <- List(true, false)) {
    test(s"caller-version-before-transitive-version-older-first-$olderFirst") {
      withCandidates(olderFirst) { (provider, current, _, newer, _) =>
        assertEquals(
          provider.definition(symbol, Some(current)).map(_.path.toURI),
          Some(newer.toURI),
        )
      }
    }

    test(s"caller-version-lsp-location-older-first-$olderFirst") {
      withCandidates(olderFirst) { (provider, current, _, newer, _) =>
        assertEquals(
          provider
            .fromSymbol(symbol, Some(current))
            .map(_.locations.get(0).getUri),
          Some(newer.toURI.toString),
        )
      }
    }

    test(s"legacy-caller-keeps-own-version-older-first-$olderFirst") {
      withCandidates(olderFirst) { (provider, _, legacy, _, older) =>
        assertEquals(
          provider.definition(symbol, Some(legacy)).map(_.path.toURI),
          Some(older.toURI),
        )
      }
    }
  }

  test("source-less-lookup-preserves-index-order") {
    withCandidates(true) { (provider, _, _, _, older) =>
      assertEquals(
        provider.definition(symbol, None).map(_.path.toURI),
        Some(older.toURI),
      )
    }
  }

  test("unregistered-worksheet-retains-global-fallback") {
    withCandidates(true) { (provider, _, _, _, older) =>
      val worksheet = workspace.resolve("scratch.worksheet.sc")
      Files.writeString(worksheet.toNIO, "example.Api.read()")
      assertEquals(
        provider.definition(symbol, Some(worksheet)).map(_.path.toURI),
        Some(older.toURI),
      )
    }
  }

  test("explicit-target-set-preserves-transitive-selection") {
    withCandidates(true) { (provider, _, _, _, older) =>
      assertEquals(
        provider
          .definition(symbol, Set(new BuildTargetIdentifier("fixture:legacy")))
          .map(_.path.toURI),
        Some(older.toURI),
      )
    }
  }

  test("transitive-source-remains-visible-without-caller-registration") {
    withCandidates(false, registerCurrent = false) {
      (provider, current, _, _, older) =>
        assertEquals(
          provider.definition(symbol, Some(current)).map(_.path.toURI),
          Some(older.toURI),
        )
    }
  }

  test("missing-caller-candidate-retains-transitive-source") {
    withCandidates(true, includeCurrent = false) {
      (provider, current, _, _, older) =>
        assertEquals(
          provider.definition(symbol, Some(current)).map(_.path.toURI),
          Some(older.toURI),
        )
    }
  }

  test(
    "caller-registration-does-not-replace-an-exact-symbol-with-an-alternative"
  ) {
    withCandidates(true, currentAlternative = true) {
      (provider, current, _, _, older) =>
        assertEquals(
          provider.definition(symbol, Some(current)).map(_.path.toURI),
          Some(older.toURI),
        )
    }
  }

  test("shared-registrations-preserve-index-order") {
    withCandidates(true, registerOlderToCurrent = true) {
      (provider, current, _, _, older) =>
        assertEquals(
          provider.definition(symbol, Some(current)).map(_.path.toURI),
          Some(older.toURI),
        )
    }
  }

  private val symbol = "example/Api#read()."

  private def withCandidates(
      olderFirst: Boolean,
      registerCurrent: Boolean = true,
      includeCurrent: Boolean = true,
      currentAlternative: Boolean = false,
      registerOlderToCurrent: Boolean = false,
  )(
      check: (
          DestinationProvider,
          AbsolutePath,
          AbsolutePath,
          AbsolutePath,
          AbsolutePath,
      ) => Unit
  ): Unit = {
    val legacy = new BuildTargetIdentifier("fixture:legacy")
    val current = new BuildTargetIdentifier("fixture:current")
    val data = new TargetData()
    def target(
        id: BuildTargetIdentifier,
        dependencies: List[BuildTargetIdentifier],
    ) = {
      import scala.jdk.CollectionConverters._
      new BuildTarget(
        id,
        Collections.emptyList(),
        Collections.singletonList("scala"),
        dependencies.asJava,
        new BuildTargetCapabilities(),
      )
    }
    data.addWorkspaceBuildTargets(
      new WorkspaceBuildTargetsResult(
        java.util.Arrays
          .asList(target(legacy, Nil), target(current, List(legacy)))
      )
    )
    val currentSource = workspace.resolve("current/Caller.scala")
    val legacySource = workspace.resolve("legacy/Caller.scala")
    for (path <- List(currentSource, legacySource)) {
      Files.createDirectories(path.toNIO.getParent)
      Files.writeString(
        path.toNIO,
        "object Caller { def value = example.Api.read() }",
      )
    }
    data.addSourceItem(currentSource, current)
    data.addSourceItem(legacySource, legacy)
    val olderJar = sourceJar("api-old-sources.jar", 1)
    val newerJar = sourceJar("api-new-sources.jar", 2)
    data.addDependencySource(olderJar, legacy)
    if (registerCurrent) data.addDependencySource(newerJar, current)
    if (registerOlderToCurrent) data.addDependencySource(olderJar, current)
    val targets = BuildTargets.from(workspace, data, tables)
    assertEquals(targets.inverseSources(currentSource), Some(current))
    assertEquals(
      targets.buildTargetTransitiveDependencies(current).toSet,
      Set(current, legacy),
    )

    val index = OnDemandSymbolIndex.empty()
    try {
      index.addSourceJar(olderJar, dialects.Scala213)
      index.addSourceJar(newerJar, dialects.Scala213)
      val definitions = index.definitions(Symbol(symbol))
      assertEquals(definitions.size, 2)
      val older = definitions
        .find(_.path.toURI.toString.contains("api-old-sources.jar"))
        .get
      val newerMethod = definitions
        .find(_.path.toURI.toString.contains("api-new-sources.jar"))
        .get
      val newer =
        if (currentAlternative)
          index
            .definitions(Symbol("example/Api#"))
            .find(_.path == newerMethod.path)
            .get
            .copy(querySymbol = Symbol(symbol))
        else newerMethod
      val expectedOlderTargets =
        if (registerOlderToCurrent) Set(legacy, current) else Set(legacy)
      assertEquals(
        targets.inverseDependencySource(older.path.jarPath.get).toSet,
        expectedOlderTargets,
      )
      assertEquals(
        targets.inverseDependencySource(newer.path.jarPath.get).toSet,
        if (registerCurrent) Set(current) else Set.empty[BuildTargetIdentifier],
      )
      val ordered =
        if (!includeCurrent) List(older)
        else if (olderFirst) List(older, newer)
        else List(newer, older)
      // JAR definitions carry ranges. Local-source rendering and the MBT
      // fallback are outside this nonempty-index fixture.
      val provider = new DestinationProvider(
        new OrderedIndex(ordered),
        null,
        () => Mtags.testingSingleton,
        workspace,
        () => None,
        targets,
        false,
        null,
        null,
        null,
      )
      check(provider, currentSource, legacySource, newer.path, older.path)
    } finally index.close()
  }

  private def sourceJar(name: String, result: Int): AbsolutePath = {
    val jar = workspace.resolve(name)
    val output = new JarOutputStream(Files.newOutputStream(jar.toNIO))
    try {
      output.putNextEntry(new JarEntry("example/Api.java"))
      output.write(
        s"package example; public final class Api { public static int read() { return $result; } }"
          .getBytes(StandardCharsets.UTF_8)
      )
      output.closeEntry()
    } finally output.close()
    jar
  }

  private class OrderedIndex(entries: List[SymbolDefinition])
      extends GlobalSymbolIndex {
    override def definition(symbol: Symbol): Option[SymbolDefinition] =
      definitions(symbol).headOption
    override def definitions(symbol: Symbol): List[SymbolDefinition] =
      entries.filter(_.querySymbol == symbol)
    override def addSourceFile(
        file: AbsolutePath,
        sourceDirectory: Option[AbsolutePath],
        dialect: Dialect,
    ): Option[IndexingResult] =
      throw new UnsupportedOperationException("read-only fixture index")
    override def addSourceJar(
        jar: AbsolutePath,
        dialect: Dialect,
        reindex: Boolean,
    ): List[IndexingResult] =
      throw new UnsupportedOperationException("read-only fixture index")
    override def addSourceDirectory(
        dir: AbsolutePath,
        dialect: Dialect,
    ): List[IndexingResult] =
      throw new UnsupportedOperationException("read-only fixture index")
  }
}

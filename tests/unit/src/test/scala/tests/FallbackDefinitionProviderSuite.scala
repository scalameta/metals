package tests

import java.nio.file.Files

import scala.meta.Dialect
import scala.meta.dialects
import scala.meta.internal.metals.DefinitionProviderReportBuilder
import scala.meta.internal.metals.FallbackDefinitionProvider
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.GlobalSymbolIndex
import scala.meta.internal.mtags.IndexingResult
import scala.meta.internal.mtags.Symbol
import scala.meta.internal.mtags.SymbolDefinition
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

/**
 * When the index still knows a definition whose sourc file has since been
 * deleted, the fallback must skip it in favour of one whose file still
 * exists.
 */
class FallbackDefinitionProviderSuite extends BaseSuite {

  /**
   * Echoes the guessed symbol for each of `paths`, preserving order.
   * Not a real [[scala.meta.internal.mtags.OnDemandSymbolIndex]]
   * that prunes stale paths itself, which would mask a regression
   * in the provider's own `path.exists` filter this suite pins.
   */
  private def indexReturning(paths: List[AbsolutePath]): GlobalSymbolIndex =
    new GlobalSymbolIndex {
      def definition(symbol: Symbol): Option[SymbolDefinition] =
        definitions(symbol).headOption
      def definitions(symbol: Symbol): List[SymbolDefinition] =
        paths.map(path =>
          SymbolDefinition(
            querySymbol = symbol,
            definitionSymbol = symbol,
            path = path,
            dialect = dialects.Scala213,
            range = Some(s.Range(0, 0, 0, 3)),
            kind = None,
            properties = 0,
          )
        )
      def addSourceFile(
          file: AbsolutePath,
          sourceDirectory: Option[AbsolutePath],
          dialect: Dialect,
      ): Option[IndexingResult] = None
      def addSourceJar(
          jar: AbsolutePath,
          dialect: Dialect,
          reindex: Boolean,
      ): List[IndexingResult] = Nil
      def addSourceDirectory(
          dir: AbsolutePath,
          dialect: Dialect,
      ): List[IndexingResult] = Nil
    }

  private val sourceText = "package example\nobject Main { val x = Foo }\n"
  // Column 23 sits inside the `Foo` identifier on line 1 (0-based).
  private val pos = new Position(1, 23)

  private def setup(): (AbsolutePath, AbsolutePath, AbsolutePath) = {
    val tmp = AbsolutePath(Files.createTempDirectory("fallback-def"))
    val existing = tmp.resolve("Existing.scala")
    Files.writeString(existing.toNIO, "class Foo")
    val deleted = tmp.resolve("Deleted.scala") // intentionally never created
    val source = tmp.resolve("Main.scala")
    Files.writeString(source.toNIO, sourceText)
    (source, existing, deleted)
  }

  private def search(
      source: AbsolutePath,
      index: GlobalSymbolIndex,
  ) = {
    val (buffers, trees) = TreeUtils.getTrees("2.13.16")
    buffers.put(source, sourceText)
    val provider =
      new FallbackDefinitionProvider(
        trees,
        index,
        (_, candidates) => candidates,
      )
    val params = new TextDocumentPositionParams(
      new TextDocumentIdentifier(source.toURI.toString),
      pos,
    )
    provider.search(
      source,
      pos,
      isScala3 = false,
      new DefinitionProviderReportBuilder(source, params, buffers),
    )
  }

  test("prefers-existing-file-over-deleted-definition") {
    val (source, existing, deleted) = setup()
    val result = search(source, indexReturning(List(deleted, existing)))
    val locations =
      result.toList.flatMap(_.locations.asScala).map(_.getUri)
    assert(locations.nonEmpty, "fallback should resolve to the existing file")
    assertEquals(
      locations.distinct,
      List(existing.toURI.toString),
      s"expected only the existing file, got: $locations",
    )
  }

  test("returns-nothing-when-the-only-definition-file-is-gone") {
    val (source, _, deleted) = setup()
    val result = search(source, indexReturning(List(deleted)))
    assert(
      result.forall(_.locations.isEmpty),
      "a definition whose file no longer exists must not be returned",
    )
  }
}

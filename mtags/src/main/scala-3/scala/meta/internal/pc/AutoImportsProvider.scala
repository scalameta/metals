package scala.meta.internal.pc

import java.nio.file.Paths
import java.{util => ju}

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.pc.AutoImports._
import scala.meta.pc._

import dotty.tools.dotc.ast.tpd._
import dotty.tools.dotc.core.Contexts._
import dotty.tools.dotc.core.Decorators._
import dotty.tools.dotc.core.Flags._
import dotty.tools.dotc.core.Names._
import dotty.tools.dotc.core.Symbols._
import dotty.tools.dotc.interactive.Interactive
import dotty.tools.dotc.interactive.InteractiveDriver
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.util.Spans
import org.eclipse.{lsp4j => l}

final class AutoImportsProvider(
    search: SymbolSearch,
    driver: MetalsDriver,
    name: String,
    params: OffsetParams,
    config: PresentationCompilerConfig
) {

  def autoImports(): List[AutoImportsResult] = {
    val uri = params.uri
    val filePath = Paths.get(uri)
    val result = driver.run(
      uri,
      params.text
    )
    val tree = result.tree

    val pos = result.positionOf(params.offset)
    val path = result.pathTo(params.offset)

    val indexedContext = IndexedContext(
      MetalsInteractive.contextOfPath(path)(using result.context)
    )
    import indexedContext.ctx

    val isSeen = mutable.Set.empty[String]
    val symbols = List.newBuilder[Symbol]
    def visit(sym: Symbol): Boolean = {
      val name = sym.denot.fullName.show
      if (!isSeen(name)) {
        isSeen += name
        symbols += sym
        true
      } else false
    }
    def isExactMatch(sym: Symbol, query: String): Boolean = {
      sym.name.show == query
    }

    val visitor = new CompilerSearchVisitor(name, visit)
    search.search(name, "", visitor)
    val results = symbols.result.filter(isExactMatch(_, name))

    if (results.nonEmpty) {
      val correctedPos = CompletionPos.infer(pos, params.text, path).sourcePos
      val generator =
        AutoImports.generator(
          correctedPos,
          params.text,
          tree,
          indexedContext.importContext,
          config
        )

      for {
        sym <- results
        edits <- generator.forSymbol(sym)
      } yield AutoImportsResultImpl(
        sym.owner.showFullName,
        edits.asJava
      )

    } else {
      List.empty
    }
  }

}

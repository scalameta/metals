package scala.meta.metals.search

import scala.meta.metals.Buffers
import scala.meta.metals.Configuration
import scala.meta.metals.Effects
import scala.meta.metals.Uri
import scala.meta.metals.index.SymbolData
import org.langmeta.lsp.SymbolInformation
import org.langmeta.jsonrpc.JsonRpcClient
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.semanticdb.Symbol
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable

trait SymbolIndex extends LazyLogging {

  /** Returns a symbol at the given location */
  def findSymbol(
      path: Uri,
      line: Int,
      column: Int
  ): Option[(Symbol, TokenEditDistance)]

  /** Returns symbol definition data from the index taking into account relevant alternatives */
  def definitionData(symbol: Symbol): Option[SymbolData]

  def findDefinition(path: Uri, line: Int, column: Int): Option[SymbolData] =
    for {
      (symbol, edit) <- findSymbol(path, line, column)
      data <- definitionData(symbol)
    } yield edit.toRevisedDefinition(data)

  /** Returns symbol data for this exact Symbol */
  def data(symbol: Symbol): Option[SymbolData]

  /** Returns symbol references data from the index taking into account relevant alternatives */
  def referencesData(symbol: Symbol): List[SymbolData]

  def findReferences(path: Uri, line: Int, column: Int): List[SymbolData] =
    for {
      (symbol, edit) <- findSymbol(path, line, column).toList
      data <- referencesData(symbol)
    } yield edit.toRevisedReferences(data)

  /** Returns symbol definitions in this workspace */
  def workspaceSymbols(query: String): List[SymbolInformation]

  def indexDependencyClasspath(
      sourceJars: List[AbsolutePath]
  ): Task[Effects.IndexSourcesClasspath]

  /** Register this Database to symbol indexer. */
  def indexDatabase(document: s.Database): Effects.IndexSemanticdb

  /** Remove any persisted files from index returning to a clean start */
  def clearIndex(): Unit

  def documentIndex: DocumentIndex

}

object SymbolIndex {
  def apply(
      cwd: AbsolutePath,
      buffers: Buffers,
      configuration: Observable[Configuration]
  )(implicit s: Scheduler, client: JsonRpcClient): SymbolIndex = {
    val symbolIndexer = new InMemorySymbolIndexer()
    val documentIndex = new InMemoryDocumentIndex()
    new InMemorySymbolIndex(
      symbolIndexer,
      documentIndex,
      cwd,
      buffers,
      configuration
    )
  }

}

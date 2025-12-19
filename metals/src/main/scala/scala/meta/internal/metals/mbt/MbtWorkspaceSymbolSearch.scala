package scala.meta.internal.metals.mbt

import java.io.Closeable

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

import scala.meta.io.AbsolutePath
import scala.meta.pc.SymbolSearch
import scala.meta.pc.SymbolSearchVisitor

import org.eclipse.{lsp4j => l}

case class MbtWorkspaceSymbolSearchParams(
    query: String,
    buildTargetIdentifier: String,
)

case class MbtPossibleReferencesParams(
    references: collection.Seq[String] = Nil,
    implementations: collection.Seq[String] = Nil,
)

trait MbtWorkspaceSymbolSearch extends Closeable {

  /**
   * Index the entire repository. Normally, this is only called once in the lifetime
   * of the process. However, it can be called multiple times if the user is flipping between
   * settings, etc.
   */
  def onReindex(): IndexingStats
  def onDidChange(file: AbsolutePath): Future[Unit]
  def onDidDelete(file: AbsolutePath): Future[Unit]
  def onDidChangeSymbols(params: OnDidChangeSymbolsParams): Future[Unit]

  def possibleReferences(
      params: MbtPossibleReferencesParams
  ): Iterable[AbsolutePath] = Nil

  /**
   * The main search API.
   *
   * @param params
   * @param visitor *must* be thread-safe.
   * @return
   */
  def workspaceSymbolSearch(
      params: MbtWorkspaceSymbolSearchParams,
      visitor: SymbolSearchVisitor,
  ): SymbolSearch.Result

  // Convenience method to avoid dealing with the visitor-based query API
  // (including concurrency).  Mostly useful for testing. In production, use the
  // visitor-based API.
  final def queryWorkspaceSymbol(
      query: String
  ): List[l.SymbolInformation] = {
    val visitor = new SimpleCollectingSymbolSearchVisitor()
    workspaceSymbolSearch(
      new MbtWorkspaceSymbolSearchParams(query, ""),
      visitor,
    )
    visitor.results.asScala.toList
  }

  def close(): Unit
}
object MbtWorkspaceSymbolSearch {
  def isRelevantPath(file: String): Boolean = {
    file.endsWith(".java") ||
    file.endsWith(".proto") ||
    file.endsWith(".scala")
  }
}

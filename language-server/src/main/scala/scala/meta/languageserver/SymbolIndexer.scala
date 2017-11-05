package scala.meta.languageserver

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.meta._
import java.util.{Map => JMap}
import com.typesafe.scalalogging.Logger
import org.langmeta.io.RelativePath
import monix.execution.Scheduler
import monix.reactive.Observable

// NOTE(olafur) it would make a lot of sense to use tries where Symbol is key.
class SymbolIndexer(
    val indexer: Observable[Unit],
    documents: JMap[RelativePath, Document],
    definitions: JMap[Symbol, Position.Range],
    definitionsByDocument: JMap[RelativePath, Iterable[Symbol]],
    references: JMap[
      Symbol,
      Map[RelativePath, List[Position]]
    ]
) {
  def goToDefinition(
      path: RelativePath,
      line: Int,
      column: Int
  ): Option[Position.Range] = {
    for {
      document <- Option(documents.get(path))
      symbol <- document.names.collectFirst {
        case ResolvedName(pos, sym, _)
            if pos.startLine == line && pos.startColumn == column =>
          sym
      }
      defn <- Option(definitions.get(symbol))
    } yield defn
  }
}
object SymbolIndexer {
  def apply(
      semanticdbs: Observable[Database],
      logger: Logger
  )(implicit s: Scheduler): SymbolIndexer = {
    val documents =
      new ConcurrentHashMap[RelativePath, Document]
    val definitions =
      new ConcurrentHashMap[Symbol, Position.Range]
    val definitionsByDocument =
      new ConcurrentHashMap[RelativePath, Iterable[Symbol]]
    val references =
      new ConcurrentHashMap[Symbol, Map[RelativePath, List[Position]]]

    def indexDocument(document: Document): Unit = {
      val input = document.input
      val filename = input.syntax
      val relpath = RelativePath(filename)
      logger.debug(s"Indexing $filename")
      val nextReferencesBySymbol = mutable.Map.empty[Symbol, List[Position]]
      val nextDefinitions = mutable.Set.empty[Symbol]

      // documents
      documents.put(relpath, document)

      // definitions
      document.names.foreach {
        case ResolvedName(pos, symbol, isDefinition) =>
          if (isDefinition) {
            definitions.put(symbol, Position.Range(input, pos.start, pos.end))
            nextDefinitions += symbol
          } else {
            nextReferencesBySymbol(symbol) =
              Position.Range(input, pos.start, pos.end) ::
                nextReferencesBySymbol.getOrElseUpdate(symbol, Nil)
          }
        case _ =>
      }

      // definitionsByFilename
      definitionsByDocument.getOrDefault(relpath, Nil).foreach {
        case sym: Symbol.Global =>
          if (!nextDefinitions.contains(sym)) {
            definitions.remove(sym) // garbage collect old symbols.
          }
        case _ =>
      }
      definitionsByDocument.put(relpath, nextDefinitions)

      // references
      nextReferencesBySymbol.foreach {
        case (symbol, referencesToSymbol) =>
          val old = references.getOrDefault(symbol, Map.empty)
          val nextReferences = old + (relpath -> referencesToSymbol)
          references.put(symbol, nextReferences)
      }
    }

    val indexer = semanticdbs.map(db => db.documents.foreach(indexDocument))

    new SymbolIndexer(
      indexer,
      documents,
      definitions,
      definitionsByDocument,
      references
    )
  }
}

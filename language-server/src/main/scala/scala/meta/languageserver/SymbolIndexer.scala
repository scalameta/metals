package scala.meta.languageserver

import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.{meta => m}
import java.util.{Map => JMap}
import com.typesafe.scalalogging.Logger
import org.langmeta.io.RelativePath
import monix.execution.Scheduler
import monix.reactive.Observable

// NOTE(olafur) it would make a lot of sense to use tries where m.Symbol is key.
class SymbolIndexer(
    val indexer: Observable[Unit],
    definitions: JMap[m.Symbol, m.Position.Range],
    definitionsByDocument: JMap[RelativePath, Iterable[m.Symbol]],
    references: JMap[
      m.Symbol,
      Map[RelativePath, List[m.Position]]
    ]
)
object SymbolIndexer {
  def apply(
      semanticdbs: Observable[m.Database],
      logger: Logger
  )(implicit s: Scheduler): SymbolIndexer = {
    val definitions =
      new ConcurrentHashMap[m.Symbol, m.Position.Range]
    val definitionsByDocument =
      new ConcurrentHashMap[RelativePath, Iterable[m.Symbol]]
    val references =
      new ConcurrentHashMap[m.Symbol, Map[RelativePath, List[m.Position]]]

    def indexDocument(document: m.Document): Unit = {
      val input = document.input
      val filename = input.syntax
      val relpath = RelativePath(filename)
      logger.debug(s"Indexing $filename")
      val nextReferencesBySymbol = mutable.Map.empty[m.Symbol, List[m.Position]]
      val nextDefinitions = mutable.Set.empty[m.Symbol]

      // definitions
      document.names.foreach {
        case m.ResolvedName(pos, symbol, isDefinition) =>
          if (isDefinition) {
            definitions.put(symbol, m.Position.Range(input, pos.start, pos.end))
            nextDefinitions += symbol
          } else {
            nextReferencesBySymbol(symbol) =
              m.Position.Range(input, pos.start, pos.end) ::
                nextReferencesBySymbol.getOrElseUpdate(symbol, Nil)
          }
        case _ =>
      }

      // definitionsByFilename
      definitionsByDocument.getOrDefault(relpath, Nil).foreach {
        case sym: m.Symbol.Global =>
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

    new SymbolIndexer(indexer, definitions, definitionsByDocument, references)
  }
}

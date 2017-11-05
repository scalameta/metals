package scala.meta.languageserver

import java.util.concurrent.ConcurrentHashMap
import java.util.{Map => JMap}
import scala.collection.mutable
import scala.meta._
import com.typesafe.scalalogging.Logger
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.io.RelativePath
import ScalametaEnrichments._
import langserver.core.Connection
import langserver.messages.MessageType

// NOTE(olafur) it would make a lot of sense to use tries where Symbol is key.
class SymbolIndexer(
    val indexer: Observable[Unit],
    logger: Logger,
    connection: Connection,
    buffers: Buffers,
    documents: JMap[RelativePath, Document],
    definitions: JMap[Symbol, Position.Range],
    denotations: JMap[Symbol, Denotation],
    references: JMap[
      Symbol,
      Map[RelativePath, List[Position]]
    ]
) {

  def documentSymbols(
      path: RelativePath
  ): Seq[(Position.Range, Denotation)] =
    for {
      document <- Option(documents.get(path)).toList
      _ <- isFreshSemanticdb(path, document).toList
      ResolvedName(pos: Position.Range, symbol: Symbol.Global, true) <- document.names
      denotation <- Option(denotations.get(symbol))
      if ! {
        import denotation._
        isPrimaryCtor ||
        isTypeParam ||
        isParam
      } // not interesting for this service
    } yield pos -> denotation

  def goToDefinition(
      path: RelativePath,
      line: Int,
      column: Int
  ): Option[Position.Range] = {
    def f(l: Int, c: Int) = s"$path:$l:$c"
    logger.trace(s"goToDefintion at ${f(line, column)}")
    for {
      document <- Option(documents.get(path))
      _ <- isFreshSemanticdb(path, document)
      _ = logger.trace(s"Database for $path")
      symbol <- document.names.collectFirst {
        case ResolvedName(pos, sym, _) if {
              logger.info(s"$sym at ${f(pos.startLine, pos.startColumn)}")
              pos.startLine <= line &&
              pos.startColumn <= column &&
              pos.endLine >= line &&
              pos.endColumn >= column
            } =>
          sym
      }
      _ = logger.trace(s"Found symbol $symbol")
      defn <- Option(definitions.get(symbol))
    } yield {
      logger.trace(s"Found definition $defn")
      defn
    }
  }

  private def isFreshSemanticdb(
      path: RelativePath,
      document: Document
  ): Option[Unit] = {
    val ok = Option(())
    buffers.read(path).fold(ok) { s =>
      if (s == document.input.contents) ok
      else {
        // NOTE(olafur) it may be a bit annoying to bail on a single character
        // edit in the file. In the future, we can try more to make sense of
        // partially fresh files using something like edit distance.
        connection.showMessage(
          MessageType.Warning,
          "Please recompile for up-to-date information"
        )
        None
      }
    }
  }

}

object SymbolIndexer {
  val emptyDocument: Document = Document(Input.None, "", Nil, Nil, Nil, Nil)
  def apply(
      semanticdbs: Observable[Database],
      logger: Logger,
      connection: Connection,
      buffers: Buffers
  )(implicit s: Scheduler): SymbolIndexer = {
    val documents =
      new ConcurrentHashMap[RelativePath, Document]
    val definitions =
      new ConcurrentHashMap[Symbol, Position.Range]
    val denotations =
      new ConcurrentHashMap[Symbol, Denotation]
    val references =
      new ConcurrentHashMap[Symbol, Map[RelativePath, List[Position]]]

    def indexDocument(document: Document): Unit = {
      val input = document.input
      val filename = input.syntax
      val relpath = RelativePath(filename)
      logger.debug(s"Indexing $filename")
      val nextReferencesBySymbol = mutable.Map.empty[Symbol, List[Position]]
      val nextDefinitions = mutable.Set.empty[Symbol]

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

      // denotations
      document.symbols.foreach {
        case ResolvedSymbol(symbol, denotation) =>
          denotations.put(symbol, denotation)
      }

      // definitionsByFilename
      documents.getOrDefault(relpath, emptyDocument).names.foreach {
        case ResolvedName(_, sym, true) =>
          if (!nextDefinitions.contains(sym)) {
            definitions.remove(sym) // garbage collect old symbols.
            denotations.remove(sym)
          }
        case _ =>
      }

      // references
      nextReferencesBySymbol.foreach {
        case (symbol, referencesToSymbol) =>
          val old = references.getOrDefault(symbol, Map.empty)
          val nextReferences = old + (relpath -> referencesToSymbol)
          references.put(symbol, nextReferences)
      }

      // documents
      documents.put(
        relpath,
        document.copy(names = document.names.sortBy(_.position.start))
      )
    }

    val indexer = semanticdbs.map(db => db.documents.foreach(indexDocument))

    new SymbolIndexer(
      indexer,
      logger,
      connection,
      buffers,
      documents,
      definitions,
      denotations,
      references
    )
  }
}

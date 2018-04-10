package scala.meta.metals.search

import java.util.concurrent.ConcurrentHashMap
import scala.meta.metals.Buffers
import scala.meta.metals.Effects
import scala.meta.metals.Configuration
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.metals.MetalsServices.cacheDirectory
import scala.meta.metals.Uri
import scala.meta.metals.compiler.CompilerConfig
import scala.meta.metals.index.SymbolData
import scala.meta.metals.mtags.Mtags
import scala.meta.metals.storage.LevelDBMap
import org.langmeta.lsp.MonixEnrichments._
import org.langmeta.lsp.SymbolInformation
import org.langmeta.jsonrpc.JsonRpcClient
import scala.meta.metals.{index => i}
import com.typesafe.scalalogging.LazyLogging
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import scala.meta.internal.semanticdb3
import scala.meta.internal.semanticdb3.SymbolOccurrence
import scala.meta.internal.semanticdb3.TextDocument
import scala.meta.internal.semanticdb3.TextDocuments
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.Symbol
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
import scala.meta.internal.semanticdb3.SymbolInformation.Kind
import scala.meta.internal.semanticdb3.SymbolInformation.Property
import scala.util.control.NonFatal

class InMemorySymbolIndex(
    val symbolIndexer: SymbolIndexer,
    val documentIndex: DocumentIndex,
    cwd: AbsolutePath,
    buffers: Buffers,
    configuration: Observable[Configuration],
)(implicit scheduler: Scheduler, client: JsonRpcClient)
    extends SymbolIndex
    with LazyLogging {
  private val config = configuration.map(_.search).toFunction0()
  private val indexedJars: ConcurrentHashMap[AbsolutePath, Unit] =
    new ConcurrentHashMap[AbsolutePath, Unit]()

  /** Returns a SymbolOccurrence at the given location */
  def resolveName(
      uri: Uri,
      line: Int,
      column: Int
  ): Option[(SymbolOccurrence, TokenEditDistance)] = {
    logger.info(s"resolveName at $uri:$line:$column")
    for {
      document <- documentIndex.getDocument(uri)
      _ = logger.info(s"Found document for $uri")
      original = Input.VirtualFile(document.uri, document.text)
      revised = uri.toInput(buffers)
      (originalPosition, edit) <- {
        findOriginalPosition(original, revised, line, column)
      }
      name <- document.occurrences.collectFirst {
        case name @ SymbolOccurrence(Some(r), symbol, _) if {
              val range = original.toIndexRange(r)
              logger.trace(
                s"${document.uri.replaceFirst(".*/", "")} [${range.pretty}] $symbol"
              )
              range.contains(originalPosition)
            } =>
          name
      }
    } yield name -> edit
  }

  /** Returns a symbol at the given location */
  def findSymbol(
      uri: Uri,
      line: Int,
      column: Int
  ): Option[(Symbol, TokenEditDistance)] = {
    for {
      (name, edit) <- resolveName(uri, line, column)
      symbol = Symbol(name.symbol)
      _ = logger.info(s"Matching symbol $symbol")
    } yield symbol -> edit
  }

  /** Returns symbol definition data from the index taking into account relevant alternatives */
  def definitionData(
      symbol: Symbol
  ): Option[SymbolData] = {
    (symbol :: symbol.definitionAlternative)
      .collectFirst {
        case symbolIndexer(data) if data.definition.nonEmpty =>
          logger.info(s"Found definition symbol ${data.symbol}")
          data
      }
  }

  def data(symbol: Symbol): Option[SymbolData] =
    symbolIndexer.get(symbol)

  /** Returns symbol references data from the index taking into account relevant alternatives */
  def referencesData(
      symbol: Symbol
  ): List[SymbolData] = {
    (symbol :: symbol.referenceAlternatives)
      .collect {
        case symbolIndexer(data) =>
          if (data.symbol != symbol.syntax)
            logger.info(s"Adding alternative references ${data.symbol}")
          data
      }
  }

  def indexDependencyClasspath(
      sourceJars: List[AbsolutePath]
  ): Task[Effects.IndexSourcesClasspath] = Task {
    if (!config().indexClasspath) Effects.IndexSourcesClasspath
    else {
      val sourceJarsWithJDK =
        if (config().indexJDK)
          CompilerConfig.jdkSources.fold(sourceJars)(_ :: sourceJars)
        else sourceJars
      val buf = List.newBuilder[AbsolutePath]
      sourceJarsWithJDK.foreach { jar =>
        // ensure we only index each jar once even under race conditions.
        // race conditions are not unlikely since multiple .compilerconfig
        // are typically created at the same time for each project/configuration
        // combination. Duplicate tasks are expensive, for example we don't want
        // to index the JDK twice on first startup.
        indexedJars.computeIfAbsent(jar, _ => buf += jar)
      }
      val sourceJarsToIndex = buf.result()
      // Acquire a lock on the leveldb cache only during indexing.
      LevelDBMap.withDB(cacheDirectory.resolve("leveldb").toFile) { db =>
        sourceJarsToIndex.foreach { path =>
          logger.info(s"Indexing classpath entry $path")
          try {
            val database = db.getOrElseUpdate[AbsolutePath, TextDocuments](
              path,
              () => Mtags.indexDatabase(path :: Nil)
            )
            indexDatabase(database)
          } catch {
            case NonFatal(e) =>
              logger.error(s"Failed to index $path", e)
          }
        }
      }
      Effects.IndexSourcesClasspath
    }
  }

  /** Register this Database to symbol indexer. */
  def indexDatabase(document: TextDocuments): Effects.IndexSemanticdb = {
    document.documents.foreach { doc =>
      try indexDocument(doc)
      catch {
        case NonFatal(e) =>
          logger.error(s"Failed to index ${doc.uri}", e)
      }
    }
    Effects.IndexSemanticdb
  }

  /**
   *
   * Register this Document to symbol indexer.
   *
   * Indexes definitions, denotations and references in this document.
   *
   * @param document Must respect the following conventions:
   *                 - filename must be formatted as a URI
   *                 - names must be sorted
   */
  def indexDocument(document: TextDocument): Effects.IndexSemanticdb = {
    val uri = Uri(document.uri)
    val input = Input.VirtualFile(document.uri, document.text)
    documentIndex.putDocument(uri, document)
    document.occurrences.foreach {
      // TODO(olafur) handle local symbols on the fly from a `Document` in go-to-definition
      // local symbols don't need to be indexed globally, by skipping them we should
      // def isLocalSymbol(sym: String): Boolean =
      // !sym.endsWith(".") &&
      //     !sym.endsWith("#") &&
      //     !sym.endsWith(")")
      // be able to minimize the size of the global index significantly.
      //      case s.SymbolOccurrence(_, sym, _) if isLocalSymbol(sym) => // Do nothing, local symbol.
      case SymbolOccurrence(Some(r), sym, SymbolOccurrence.Role.DEFINITION) =>
        symbolIndexer.addDefinition(
          sym,
          i.Position(document.uri, input.toIndexRange(r))
        )
      case SymbolOccurrence(Some(r), sym, SymbolOccurrence.Role.REFERENCE) =>
        symbolIndexer.addReference(
          Uri(document.uri),
          input.toIndexRange(r),
          sym
        )
      case _ =>
    }
    document.symbols.foreach(symbolIndexer.addSymbolInformation)
    Effects.IndexSemanticdb
  }

  override def workspaceSymbols(query: String): List[SymbolInformation] = {
    import scala.meta.metals.ScalametaEnrichments._
    val result = symbolIndexer.allSymbols.toIterator
      .withFilter { symbol =>
        symbol.definition.isDefined && symbol.definition.get.uri
          .startsWith("file:")
      }
      .collect {
        case i.SymbolData(sym, Some(pos), _, Some(info))
            if info.isOneOf(
              Kind.CLASS,
              Kind.OBJECT,
              Kind.TRAIT,
              Kind.INTERFACE
              // Blocked by https://github.com/scalameta/scalameta/issues/1484, package object has name "package".
              // Kind.PACKAGE_OBJECT
            ) && {
              // NOTE(olafur) fuzzy-wuzzy doesn't seem to do a great job
              // for camelcase searches like "DocSymPr" when looking for
              // "DocumentSymbolProvider. We should try and port something
              // like https://blog.forrestthewoods.com/reverse-engineering-sublime-text-s-fuzzy-match-4cffeed33fdb
              // instead.
              FuzzySearch.partialRatio(query, info.name) >= 90
            } =>
          SymbolInformation(
            info.name,
            info.toSymbolKind,
            pos.toLocation,
            Some(sym.stripPrefix("_root_."))
          )
      }
    result.toList
  }

  def clearIndex(): Unit = indexedJars.clear()

  /** Returns the matching position in the original document.
   *
   * Falls back to TokenEditDistance in case the current open buffer
   * is off-sync with the latest saved semanticdb document.
   */
  private def findOriginalPosition(
      original: Input.VirtualFile,
      revised: Input.VirtualFile,
      line: Int,
      column: Int
  ): Option[(Position, TokenEditDistance)] = {
    if (original.value == revised.value) {
      // Minor optimization, skip edit-distance when original is synced
      Some(original.toPosition(line, column) -> TokenEditDistance.empty)
    } else {
      for {
        edit <- TokenEditDistance(original, revised)
        revisedOffset = revised.toOffset(line, column)
        originalPosition <- edit.toOriginal(revisedOffset).right.toOption
      } yield originalPosition -> edit
    }
  }

}

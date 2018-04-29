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
import io.github.lsp4s.jsonrpc.JsonRpcClient
import scala.meta.metals.{index => i}
import com.typesafe.scalalogging.LazyLogging
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.internal.semanticdb.schema.ResolvedName
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.SemanticdbEnrichments._
import org.langmeta.semanticdb.Symbol
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.Observable
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

  /** Returns a ResolvedName at the given location */
  def resolveName(
      uri: Uri,
      line: Int,
      column: Int
  ): Option[(ResolvedName, TokenEditDistance)] = {
    logger.info(s"resolveName at $uri:$line:$column")
    for {
      document <- documentIndex.getDocument(uri)
      _ = logger.info(s"Found document for $uri")
      original = Input.VirtualFile(document.filename, document.contents)
      revised = uri.toInput(buffers)
      (originalPosition, edit) <- {
        findOriginalPosition(original, revised, line, column)
      }
      name <- document.names.collectFirst {
        case name @ ResolvedName(Some(position), symbol, _) if {
              val range = original.toIndexRange(position.start, position.end)
              logger.trace(
                s"${document.filename.replaceFirst(".*/", "")} [${range.pretty}] ${symbol}"
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
      _ = logger.info(s"Matching symbol ${symbol}")
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
            val database = db.getOrElseUpdate[AbsolutePath, Database](
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
  def indexDatabase(document: s.Database): Effects.IndexSemanticdb = {
    document.documents.foreach { doc =>
      try indexDocument(doc)
      catch {
        case NonFatal(e) =>
          logger.error(s"Failed to index ${doc.filename}", e)
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
  def indexDocument(document: s.Document): Effects.IndexSemanticdb = {
    val uri = Uri(document.filename)
    val input = Input.VirtualFile(document.filename, document.contents)
    documentIndex.putDocument(uri, document)
    document.names.foreach {
      // TODO(olafur) handle local symbols on the fly from a `Document` in go-to-definition
      // local symbols don't need to be indexed globally, by skipping them we should
      // def isLocalSymbol(sym: String): Boolean =
      // !sym.endsWith(".") &&
      //     !sym.endsWith("#") &&
      //     !sym.endsWith(")")
      // be able to minimize the size of the global index significantly.
      //      case s.ResolvedName(_, sym, _) if isLocalSymbol(sym) => // Do nothing, local symbol.
      case s.ResolvedName(Some(s.Position(start, end)), sym, true) =>
        symbolIndexer.addDefinition(
          sym,
          i.Position(document.filename, Some(input.toIndexRange(start, end)))
        )
      case s.ResolvedName(Some(s.Position(start, end)), sym, false) =>
        symbolIndexer.addReference(
          document.filename,
          input.toIndexRange(start, end),
          sym
        )
      case _ =>
    }
    document.symbols.foreach {
      case s.ResolvedSymbol(sym, Some(denot)) =>
        symbolIndexer.addDenotation(
          sym,
          denot.flags,
          denot.name,
          denot.signature
        )
      case _ =>
    }
    Effects.IndexSemanticdb
  }

  override def workspaceSymbols(query: String): List[SymbolInformation] = {
    import scala.meta.metals.ScalametaEnrichments._
    import scala.meta.semanticdb._
    val result = symbolIndexer.allSymbols.toIterator
      .withFilter { symbol =>
        symbol.definition.isDefined && symbol.definition.get.uri
          .startsWith("file:")
      }
      .collect {
        case i.SymbolData(sym, Some(pos), _, flags, name, _)
            if flags.hasOneOfFlags(CLASS | TRAIT | OBJECT) && {
              // NOTE(olafur) fuzzy-wuzzy doesn't seem to do a great job
              // for camelcase searches like "DocSymPr" when looking for
              // "DocumentSymbolProvider. We should try and port something
              // like https://blog.forrestthewoods.com/reverse-engineering-sublime-text-s-fuzzy-match-4cffeed33fdb
              // instead.
              FuzzySearch.partialRatio(query, name) >= 90
            } =>
          SymbolInformation(
            name,
            flags.toSymbolKind,
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

package scala.meta.languageserver.search

import java.util.concurrent.ConcurrentHashMap
import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Effects
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.Uri
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.index.SymbolData
import scala.meta.languageserver.mtags.Mtags
import scala.meta.languageserver.storage.LevelDBMap
import scala.meta.languageserver.{index => i}
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.types.SymbolInformation
import me.xdrop.fuzzywuzzy.FuzzySearch
import org.langmeta.inputs.Input
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.internal.semanticdb.schema.ResolvedName
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.SemanticdbEnrichments._
import org.langmeta.semanticdb.Symbol

class InMemorySymbolIndex(
    val symbolIndexer: SymbolIndexer,
    val documentIndex: DocumentIndex,
    cwd: AbsolutePath,
    notifications: Notifications,
    buffers: Buffers,
    serverConfig: ServerConfig,
) extends SymbolIndex
    with LazyLogging {
  private val indexedJars: ConcurrentHashMap[AbsolutePath, Unit] =
    new ConcurrentHashMap[AbsolutePath, Unit]()

  /** Returns a ResolvedName at the given location */
  def resolveName(
      uri: Uri,
      line: Int,
      column: Int
  ): Option[ResolvedName] = {
    logger.info(s"resolveName at $uri:$line:$column")
    for {
      document <- documentIndex.getDocument(uri)
      _ = logger.info(s"Found document for $uri")
      input = Input.VirtualFile(document.filename, document.contents)
      name <- document.names.collectFirst {
        case name @ ResolvedName(Some(position), symbol, _) if {
              val range = input.toIndexRange(position.start, position.end)
              logger.trace(
                s"${document.filename.replaceFirst(".*/", "")} [${range.pretty}] ${symbol}"
              )
              range.contains(line, column)
            } =>
          name
      }
    } yield name
  }

  /** Returns a symbol at the given location */
  def findSymbol(
      uri: Uri,
      line: Int,
      column: Int
  ): Option[Symbol] = {
    for {
      name <- resolveName(uri, line, column)
      symbol = Symbol(name.symbol)
      _ = logger.info(s"Matching symbol ${symbol}")
    } yield symbol
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
  ): Effects.IndexSourcesClasspath = {
    if (!serverConfig.indexClasspath) Effects.IndexSourcesClasspath
    else {
      val sourceJarsWithJDK =
        if (serverConfig.indexJDK)
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
          logger.info(s"Indexing classpath entry $path...")
          val database = db.getOrElseUpdate[AbsolutePath, Database](path, {
            () =>
              Mtags.indexDatabase(path :: Nil)
          })
          indexDatabase(database)
        }
      }
      Effects.IndexSourcesClasspath
    }
  }

  /** Register this Database to symbol indexer. */
  def indexDatabase(document: s.Database): Effects.IndexSemanticdb = {
    document.documents.foreach(indexDocument)
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
    import scala.meta.languageserver.ScalametaEnrichments._
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
}

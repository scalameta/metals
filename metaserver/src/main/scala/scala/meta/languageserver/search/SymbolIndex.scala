package scala.meta.languageserver.search

import java.net.URI
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import scala.meta.languageserver.Buffers
import scala.meta.languageserver.Effects
import scala.meta.languageserver.ScalametaEnrichments._
import scala.meta.languageserver.ServerConfig
import scala.meta.languageserver.compiler.CompilerConfig
import scala.meta.languageserver.mtags.Mtags
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.storage.LevelDBMap
import scala.meta.languageserver.{index => i}
import `scala`.meta.languageserver.index.Position
import `scala`.meta.languageserver.index.SymbolData
import com.typesafe.scalalogging.LazyLogging
import langserver.{types => l}
import langserver.core.Notifications
import langserver.messages.DefinitionResult
import langserver.messages.ReferencesResult
import langserver.messages.DocumentSymbolResult
import org.langmeta.inputs.Input
import org.langmeta.internal.io.FileIO
import org.langmeta.internal.semanticdb.schema.Database
import org.langmeta.internal.semanticdb.schema.Document
import org.langmeta.internal.semanticdb.schema.ResolvedName
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import org.langmeta.languageserver.InputEnrichments._
import org.langmeta.semanticdb.Signature
import org.langmeta.semanticdb.Symbol

/**
 * A high-level wrapper around [[DocumentIndex]] and [[SymbolIndexer]].
 *
 * Can respond to high-level queries like "go to definition" and "find references".
 */
class SymbolIndex(
    val symbolIndexer: SymbolIndexer,
    val documentIndex: DocumentIndex,
    cwd: AbsolutePath,
    notifications: Notifications,
    buffers: Buffers,
    serverConfig: ServerConfig,
) extends LazyLogging {
  private val indexedJars: ConcurrentHashMap[AbsolutePath, Unit] =
    new ConcurrentHashMap[AbsolutePath, Unit]()

  /** Returns a ResolvedName at the given location */
  def resolveName(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[ResolvedName] = {
    logger.info(s"resolveName at $path:$line:$column")
    for {
      document <- documentIndex.getDocument(path.toNIO.toUri)
      _ = logger.info(s"Found document for $path")
      _ <- isFreshSemanticdb(path, document)
      input = Input.VirtualFile(document.filename, document.contents)
      _ = logger.info(s"Document for $path is fresh")
      name <- document.names.collectFirst {
        case name @ ResolvedName(Some(position), symbol, _) if {
              val range = input.toIndexRange(position.start, position.end)
              logger.debug(
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
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[Symbol] = {
    for {
      name <- resolveName(path, line, column)
      symbol = Symbol(name.symbol)
      _ = logger.info(s"Matching symbol ${symbol}")
    } yield symbol
  }

  /** Returns symbol definition data from the index taking into account relevant alternatives */
  def definitionData(
      symbol: Symbol
  ): Option[SymbolData] = {
    (symbol :: alternatives.forDefinition(symbol))
      .collectFirst {
        case symbolIndexer(data) if data.definition.nonEmpty =>
          logger.info(s"Found definition symbol ${data.symbol}")
          data
      }
  }

  /** Returns the definition location of the given symbol index data
   * @param symbolData symbol data retrieved with [[definitionData]]
   */
  // TODO(alexey) this is not index-specific and should be moved to some SymbolData-ops
  def definitionLocation(
      symbolData: SymbolData
  ): Option[l.Location] = {
    for {
      i.Position(uri, Some(range)) <- symbolData.definition
      _ = logger.info(
        s"Found definition ${uri.replaceFirst(".*/", "")} [${range.pretty}] ${symbolData.symbol}"
      )
    } yield l.Location(uri, range.toRange)
  }

  /** Returns symbol references data from the index taking into account relevant alternatives */
  def referencesData(
      symbol: Symbol
  ): List[SymbolData] = {
    (symbol :: alternatives.forReferences(symbol))
      .collect {
        case symbolIndexer(data) =>
          if (data.symbol != symbol.syntax)
            logger.info(s"Adding alternative references ${data.symbol}")
          data
      }
  }

  /** Returns references locations for the given symbol index data
   * @param symbolData symbol data retrieved with [[referencesData]]
   * @param withDefinition if set to `true` will include symbol definition location
   */
  // TODO(alexey) this is not index-specific and should be moved to some SymbolData-ops
  def referencesLocations(
      symbolData: SymbolData,
      withDefinition: Boolean
  ): Set[l.Location] = {
    def positionToRef(pos: i.Position): (String, i.Ranges) =
      (pos.uri, i.Ranges(pos.range.toSeq))

    val definitionRef =
      if (withDefinition) symbolData.definition.map(positionToRef) else None

    for {
      (uri, ranges) <- (symbolData.references.toSet ++ definitionRef.toSet)
      if (!uri.startsWith("jar:file")) // definition may refer to a jar
      range <- ranges.ranges
      _ = logger.info(
        s"Found reference ${uri.replaceFirst(".*/", "")} [${range.pretty}] ${symbolData.symbol}"
      )
    } yield l.Location(uri, range.toRange)
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
   *                 - filename must be a URI
   *                 - names must be sorted
   */
  def indexDocument(document: s.Document): Effects.IndexSemanticdb = {
    val input = Input.VirtualFile(document.filename, document.contents)
    // what do we put as the uri?
    val uri = URI.create(document.filename)
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

  /**
   * Returns false this this document is stale.
   *
   * A document is considered stale if it's off-sync with the contents in [[buffers]].
   */
  private def isFreshSemanticdb(
      path: AbsolutePath,
      document: Document
  ): Option[Unit] = {
    val ok = Option(())
    val s = buffers.read(path)
    if (s == document.contents) ok
    else {
      // NOTE(olafur) it may be a bit annoying to bail on a single character
      // edit in the file. In the future, we can try more to make sense of
      // partially fresh files using something like edit distance.
      notifications.showMessage(
        l.MessageType.Warning,
        "Please recompile for up-to-date information"
      )
      None
    }
  }

  // TODO(alexey) this is not index specific and could be moved somewhere else (like Symbol-ops)
  case object alternatives {

    /** Returns a list of fallback symbols that can act instead of given symbol. */
    // TODO(alexey) review this list
    def forReferences(symbol: Symbol): List[Symbol] = {
      List(
        caseClassCompanionToType,
        caseClassApplyOrCopyParams
      ).flatMap { _.lift(symbol) }
    }

    /** Returns a list of fallback symbols that can act instead of given symbol. */
    def forDefinition(symbol: Symbol): List[Symbol] = {
      List(
        caseClassCompanionToType,
        caseClassApplyOrCopy,
        caseClassApplyOrCopyParams,
        methodToTerm
      ).flatMap { _.lift(symbol) }
    }

    /** If `case class A(a: Int)` and there is no companion object, resolve
     * `A` in `A(1)` to the class definition.
     */
    val caseClassCompanionToType: PartialFunction[Symbol, Symbol] = {
      case Symbol.Global(owner, Signature.Term(name)) =>
        Symbol.Global(owner, Signature.Type(name))
    }

    /** If `case class Foo(a: Int)`, then resolve
     * `a` in `Foo.apply(a = 1)`, and
     * `a` in `Foo(1).copy(a = 2)`
     * to the `Foo.a` primary constructor definition.
     */
    val caseClassApplyOrCopyParams: PartialFunction[Symbol, Symbol] = {
      case Symbol.Global(
          Symbol.Global(
            Symbol.Global(owner, signature),
            Signature.Method("copy" | "apply", _)
          ),
          param: Signature.TermParameter
          ) =>
        Symbol.Global(
          Symbol.Global(owner, Signature.Type(signature.name)),
          param
        )
    }

    /** If `case class Foo(a: Int)`, then resolve
     * `apply` in `Foo.apply(1)`, and
     * `copy` in `Foo(1).copy(a = 2)`
     * to the `Foo` class definition.
     */
    val caseClassApplyOrCopy: PartialFunction[Symbol, Symbol] = {
      case Symbol.Global(
          Symbol.Global(owner, signature),
          Signature.Method("apply" | "copy", _)
          ) =>
        Symbol.Global(owner, Signature.Type(signature.name))
    }

    // FIXME(alexey) I'm not sure what this transformation does
    val methodToTerm: PartialFunction[Symbol, Symbol] = {
      case Symbol.Global(owner, Signature.Method(name, _)) =>
        Symbol.Global(owner, Signature.Term(name))
    }
  }

}

object SymbolIndex {

  def empty(cwd: AbsolutePath): SymbolIndex =
    apply(cwd, (_, _) => (), Buffers(), ServerConfig(cwd))

  def apply(
      cwd: AbsolutePath,
      notifications: Notifications,
      buffers: Buffers,
      serverConfig: ServerConfig
  ): SymbolIndex = {
    val symbolIndexer = new TrieMapSymbolIndexer()
    val documentIndex = new InMemoryDocumentIndex()
    new SymbolIndex(
      symbolIndexer,
      documentIndex,
      cwd,
      notifications,
      buffers,
      serverConfig
    )
  }

}

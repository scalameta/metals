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
import scala.meta.languageserver.ctags.Ctags
import scala.meta.languageserver.ScalametaLanguageServer.cacheDirectory
import scala.meta.languageserver.storage.LevelDBMap
import scala.meta.languageserver.{index => i}
import `scala`.meta.languageserver.index.Position
import `scala`.meta.languageserver.index.SymbolData
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Notifications
import langserver.messages.DefinitionResult
import langserver.messages.DocumentSymbolResult
import langserver.messages.MessageType
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
    val symbols: SymbolIndexer,
    val documents: DocumentIndex,
    cwd: AbsolutePath,
    notifications: Notifications,
    buffers: Buffers,
    serverConfig: ServerConfig,
) extends LazyLogging {
  private val indexedJars: ConcurrentHashMap[AbsolutePath, Unit] =
    new ConcurrentHashMap[AbsolutePath, Unit]()

  /** Returns a symbol at the given location with a non-empty definition */
  def findSymbol(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[SymbolData] = {
    logger.info(s"findSymbol at $path:$line:$column")
    for {
      document <- documents.getDocument(path.toNIO.toUri)
      _ = logger.info(s"Found document for $path")
      _ <- isFreshSemanticdb(path, document)
      input = Input.VirtualFile(document.filename, document.contents)
      _ = logger.info(s"Document for $path is fresh")
      name <- document.names.collectFirst {
        case name @ ResolvedName(Some(position), sym, _) if {
              val pos = input.toIndexRange(position.start, position.end)
              logger.info(
                s"$sym at ${document.filename
                  .replaceFirst(".*/", "")}:${pos.startLine}:${pos.startColumn}-${pos.endLine}:${pos.endColumn}"
              )
              pos.startLine <= line &&
              pos.startColumn <= column &&
              pos.endLine >= line &&
              pos.endColumn >= column
            } =>
          name
      }
      msym = Symbol(name.symbol)
      _ = logger.info(s"Found matching symbol $msym")
      symbol <- symbols.get(name.symbol).orElse {
        val alts = alternatives(msym)
        logger.info(s"Trying alternatives: ${alts.mkString(" | ")}")
        alts.collectFirst { case symbols(alternative) => alternative }
      }
      _ = logger.info(
        s"Found matching symbol index ${symbol.name}: ${symbol.signature}"
      )
    } yield symbol
  }

  /** Returns the definition position of the symbol at the given position */
  def goToDefinition(
      path: AbsolutePath,
      line: Int,
      column: Int
  ): Option[DefinitionResult] = {
    for {
      symbol <- findSymbol(path, line, column)
      definition <- symbol.definition
    } yield {
      val nonJarDefinition: Position =
        if (definition.uri.startsWith("jar:file")) {
          definition.withUri(
            createFileInWorkspaceTarget(URI.create(definition.uri)).toString
          )
        } else definition
      logger.info(s"Found definition $nonJarDefinition")
      val location = nonJarDefinition.toLocation
      DefinitionResult(location :: Nil)
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
              Ctags.indexDatabase(path :: Nil)
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
    documents.putDocument(uri, document)
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
        symbols.addDefinition(
          sym,
          i.Position(document.filename, Some(input.toIndexRange(start, end)))
        )
      case s.ResolvedName(Some(s.Position(start, end)), sym, false) =>
        symbols.addReference(
          document.filename,
          input.toIndexRange(start, end),
          sym
        )
      case _ =>
    }
    document.symbols.foreach {
      case s.ResolvedSymbol(sym, Some(denot)) =>
        symbols.addDenotation(sym, denot.flags, denot.name, denot.signature)
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
        MessageType.Warning,
        "Please recompile for up-to-date information"
      )
      None
    }
  }

  /** Returns a list of fallback symbols that can act instead of given symbol. */
  private def alternatives(symbol: Symbol): List[Symbol] =
    symbol match {
      case Symbol.Global(owner, Signature.Term(name)) =>
        // If `case class A(a: Int)` and there is no companion object, resolve
        // `A` in `A(1)` to the class definition.
        Symbol.Global(owner, Signature.Type(name)) :: Nil
      case Symbol.Multi(ss) =>
        // If `import a.B` where `case class B()`, then
        // resolve to either symbol, whichever has a definition.
        ss
      case Symbol.Global(
          companion @ Symbol.Global(owner, signature),
          Signature.Method("apply" | "copy", _)
          ) =>
        // If `case class Foo(a: Int)`, then resolve
        // `apply` in `Foo.apply(1)`, and
        // `copy` in `Foo(1).copy(a = 2)`
        // to the `Foo` class definition.
        companion :: Symbol.Global(owner, Signature.Type(signature.name)) :: Nil
      case Symbol.Global(owner, Signature.Method(name, _)) =>
        Symbol.Global(owner, Signature.Term(name)) :: Nil
      case Symbol.Global(
          Symbol.Global(
            Symbol.Global(owner, signature),
            Signature.Method("copy" | "apply", _)
          ),
          param: Signature.TermParameter
          ) =>
        // If `case class Foo(a: Int)`, then resolve
        // `a` in `Foo.apply(a = 1)`, and
        // `a` in `Foo(1).copy(a = 2)`
        // to the `Foo.a` primary constructor definition.
        Symbol.Global(
          Symbol.Global(owner, Signature.Type(signature.name)),
          param
        ) :: Nil
      case _ =>
        logger.info(s"Found no alternative for ${symbol.structure}")
        Nil
    }

  // Writes the contents from in-memory source file to a file in the target/source/*
  // directory of the workspace. vscode has support for TextDocumentContentProvider
  // which can provide hooks to open readonly views for custom uri schemes:
  // https://code.visualstudio.com/docs/extensionAPI/vscode-api#TextDocumentContentProvider
  // However, that is a vscode only solution and we'd like this work for all
  // text editors. Therefore, we write instead the file contents to disk in order to
  // return a file: uri.
  // TODO: Fix this with https://github.com/scalameta/language-server/issues/36
  private def createFileInWorkspaceTarget(
      uri: URI
  ): URI = {
    logger.info(
      s"Jumping into uri $uri, writing contents to file in target file"
    )
    val contents = new String(FileIO.readAllBytes(uri), StandardCharsets.UTF_8)
    // HACK(olafur) URIs are not typesafe, jar:file://blah.scala will return
    // null for `.getPath`. We should come up with nicer APIs to deal with this
    // kinda stuff.
    val path: String =
      if (uri.getPath == null)
        uri.getSchemeSpecificPart
      else uri.getPath
    val filename = Paths.get(path).getFileName
    val dir = cwd.resolve("target").resolve("sources")
    Files.createDirectories(dir.toNIO)
    val out = dir.toNIO.resolve(filename)
    Files.write(out, contents.getBytes(StandardCharsets.UTF_8))
    out.toUri
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
    val symbols = new TrieMapSymbolIndexer()
    val documents = new InMemoryDocumentIndex()
    new SymbolIndex(
      symbols,
      documents,
      cwd,
      notifications,
      buffers,
      serverConfig
    )
  }

}

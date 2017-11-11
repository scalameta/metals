package scala.meta.languageserver

import java.nio.file.Files
import java.nio.file.Paths
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
)(implicit cwd: AbsolutePath) {

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
    logger.info(s"goToDefintion at $path:$line:$column")
    for {
      name <- resolvedNameAt(path, line, column)
      symbol = name.symbol
      _ = logger.info(s"Found symbol $symbol")
      defn <- definition(symbol).orElse {
        alternatives(symbol).flatMap { alternative =>
          logger.info(s"Trying alternative symbol $alternative")
          definition(alternative)
        }.headOption
      }
    } yield {
      logger.trace(s"Found definition $defn")
      defn
    }
  }

  private def definition(symbol: Symbol): Option[Position.Range] =
    Option(definitions.get(symbol)).map {
      case Position.Range(input @ Input.VirtualFile(path, _), start, end)
          if path.contains("jar") =>
        Position.Range(createFileInWorkspaceTarget(input), start, end)
      case pos => pos
    }

  // Writes the contents from in-memory source file to a file in the target/source/*
  // directory of the workspace. vscode has support for TextDocumentContentProvider
  // which can provide hooks to open readonly views for custom uri schemes:
  // https://code.visualstudio.com/docs/extensionAPI/vscode-api#TextDocumentContentProvider
  // However, that is a vscode only solution and we'd like this work for all
  // text editors. Therefore, we write instead the file contents to disk in order to
  // return a file: uri.
  private def createFileInWorkspaceTarget(
      input: Input.VirtualFile
  ): Input.VirtualFile = {
    logger.info(
      s"Jumping into jar ${input.path}, writing contents to file in target file"
    )
    val dir = cwd.resolve("target").resolve("sources")
    Files.createDirectories(dir.toNIO)
    val out = dir.toNIO.resolve(Paths.get(input.path).getFileName)
    Files.write(out, input.contents.getBytes())
    Input.VirtualFile(cwd.toNIO.relativize(out).toString, input.contents)
  }

  private def alternatives(symbol: Symbol): List[Symbol] =
    symbol match {
      case Symbol.Global(
          companion @ Symbol.Global(owner, signature),
          Signature.Method("apply" | "copy", _)
          ) =>
        // If `case class Foo(a: Int)`, then resolve
        // `apply` in `Foo.apply(1)`, and
        // `copy` in `Foo(1).copy(a = 2)`
        // to the `Foo` class definition.
        companion :: Symbol.Global(owner, Signature.Type(signature.name)) :: Nil
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
      case Symbol.Global(owner, Signature.Term(name)) =>
        // If `case class A(a: Int)` and there is no companion object, resolve
        // `A` in `A(1)` to the class definition.
        Symbol.Global(owner, Signature.Type(name)) :: Nil
      case Symbol.Multi(symbols) =>
        // If `import a.B` where `case class B()`, then
        // resolve to either symbol, whichever has a definition.
        symbols
      case Symbol.Global(owner, Signature.Method(name, _)) =>
        Symbol.Global(owner, Signature.Term(name)) :: Nil
      case _ =>
        logger.trace(s"Found no alternative for ${symbol.structure}")
        Nil
    }

  private def companionClass(symbol: Symbol): Option[Symbol] =
    symbol match {
      case Symbol.Global(owner, Signature.Term(name)) =>
        Some(Symbol.Global(owner, Signature.Type(name)))
      case _ => None
    }

  private def isFreshSemanticdb(
      path: RelativePath,
      document: Document
  ): Option[Unit] = {
    val ok = Option(())
    val s = buffers.read(path)
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

  private def resolvedNameAt(
      path: RelativePath,
      line: Int,
      column: Int
  ): Option[ResolvedName] =
    for {
      document <- Option(documents.get(path))
      _ <- isFreshSemanticdb(path, document)
      _ = logger.info(s"Database for $path")
      name <- document.names.collectFirst {
        case name @ ResolvedName(pos, sym, _) if {
              logger.info(s"$sym at ${pos.location}")
              pos.startLine <= line &&
              pos.startColumn <= column &&
              pos.endLine >= line &&
              pos.endColumn >= column
            } =>
          name
      }
    } yield name

}

object SymbolIndexer {
  val emptyDocument: Document = Document(Input.None, "", Nil, Nil, Nil, Nil)
  def apply(
      semanticdbs: Observable[Database],
      logger: Logger,
      connection: Connection,
      buffers: Buffers
  )(implicit s: Scheduler, cwd: AbsolutePath): SymbolIndexer = {
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
      if (!filename.startsWith("jar")) {
        logger.debug(s"Indexing $filename")
      }
      val nextReferencesBySymbol = mutable.Map.empty[Symbol, List[Position]]
      val nextDefinitions = mutable.Set.empty[Symbol]

      // definitions
      document.names.foreach {
        case ResolvedName(pos, symbol, isDefinition) =>
          if (isDefinition) {
            logger.trace(s"Definition of $symbol at ${pos.location}")
            definitions.put(symbol, Position.Range(input, pos.start, pos.end))
            nextDefinitions += symbol
          } else {
            logger.trace(s"Reference to $symbol at ${pos.location}")
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

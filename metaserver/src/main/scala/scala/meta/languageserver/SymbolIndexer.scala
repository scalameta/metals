package scala.meta.languageserver

import java.net.URI
import java.nio.file.Paths
import scala.meta.languageserver.index.DocumentStore
import scala.meta.languageserver.index.InMemoryDocumentStore
import scala.meta.languageserver.{index => i}
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Connection
import langserver.messages.DefinitionResult
import langserver.messages.DocumentSymbolResult
import langserver.messages.MessageType
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.inputs.Input
import org.langmeta.inputs.Position
import org.langmeta.internal.semanticdb.schema.Document
import org.langmeta.internal.semanticdb.schema.ResolvedName
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import org.langmeta.semanticdb.Signature
import org.langmeta.semanticdb.Symbol
import org.langmeta.languageserver.InputEnrichments._
import scala.meta.languageserver.ScalametaEnrichments._
import langserver.types.Location

// NOTE(olafur) it would make a lot of sense to use tries where Symbol is key.
class SymbolIndexer(
    symbols: SymbolIndexerMap,
    documents: DocumentStore,
    connection: Connection,
    buffers: Buffers
)(implicit cwd: AbsolutePath)
    extends LazyLogging {

  def goToDefinition(
      path: RelativePath,
      line: Int,
      column: Int
  ): Option[DefinitionResult] = {
    logger.info(s"goToDefintion at $path:$line:$column")
    // NOTE(olafur) this is false to assume that the filename is relative
    // to cwd, what about jars?
    for {
      document <- documents.getDocument(URI.create(s"file:$path"))
      _ <- isFreshSemanticdb(path, document)
      input = Input.VirtualFile(document.filename, document.contents)
      _ = logger.info(s"Database for $path")
      name <- document.names.collectFirst {
        case name @ ResolvedName(Some(position), sym, _) if {
              val pos = input.toIndexRange(position.start, position.end)
              logger.info(
                s"$sym at ${document.filename}${pos.startLine}:${pos.startColumn}"
              )
              pos.startLine <= line &&
              pos.startColumn <= column &&
              pos.endLine >= line &&
              pos.endColumn >= column
            } =>
          name
      }
      symbol <- symbols.get(name.symbol)
      definition <- symbol.definition
    } yield DefinitionResult(definition.toLocation :: Nil)
  }
  private def isFreshSemanticdb(
      path: RelativePath,
      document: Document
  ): Option[Unit] = {
    val ok = Option(())
    val s = buffers.read(path)
    if (s == document.contents) ok
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
      case Symbol.Multi(ss) =>
        // If `import a.B` where `case class B()`, then
        // resolve to either symbol, whichever has a definition.
        ss
      case Symbol.Global(owner, Signature.Method(name, _)) =>
        Symbol.Global(owner, Signature.Term(name)) :: Nil
      case _ =>
        logger.trace(s"Found no alternative for ${symbol.structure}")
        Nil
    }
  def indexDocument(document: s.Document): Unit = {
    val input = Input.VirtualFile(document.filename, document.contents)
    // what do we put as the uri?
    val uriS = "file:" + document.filename
    val uri = URI.create(uriS)
    documents.putDocument(uri, document)
    document.names.foreach {
      case s.ResolvedName(_, sym, _)
          if !sym.endsWith(".") && !sym.endsWith("#") =>
      // Do nothing, local symbol.
      case s.ResolvedName(Some(s.Position(start, end)), sym, true) =>
        symbols.addDefinition(
          sym,
          i.Position(uriS, Some(input.toIndexRange(start, end)))
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
  }

}

object SymbolIndexer {
  def apply(
      connection: Connection,
      buffers: Buffers
  )(implicit scheduler: Scheduler, cwd: AbsolutePath): SymbolIndexer = {
    val symbols = new SymbolIndexerMap()
    val documents = new InMemoryDocumentStore()

    new SymbolIndexer(
      symbols,
      documents,
      connection,
      buffers
    )
  }
}

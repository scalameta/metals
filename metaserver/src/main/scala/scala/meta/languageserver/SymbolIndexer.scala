package scala.meta.languageserver

import java.net.URI
import scala.meta.languageserver.index.DocumentStore
import scala.meta.languageserver.index.InMemoryDocumentStore
import scala.meta.languageserver.{index => i}
import com.typesafe.scalalogging.LazyLogging
import langserver.core.Connection
import monix.execution.Scheduler
import monix.reactive.Observable
import org.langmeta.inputs.Input.Denotation
import org.langmeta.inputs.Position
import org.langmeta.semanticdb.Symbol
import org.langmeta.semanticdb.Signature
import org.langmeta.internal.semanticdb.{schema => s}
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath

// NOTE(olafur) it would make a lot of sense to use tries where Symbol is key.
class SymbolIndexer(
    val indexer: Observable[Effects.IndexSemanticdb],
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
  ): Option[Position.Range] = {
    logger.info(s"goToDefintion at $path:$line:$column")
    ???
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

  private def companionClass(symbol: Symbol): Option[Symbol] =
    symbol match {
      case Symbol.Global(owner, Signature.Term(name)) =>
        Some(Symbol.Global(owner, Signature.Type(name)))
      case _ => None
    }

}

object SymbolIndexer {
  def apply(
      semanticdbs: Observable[s.Database],
      connection: Connection,
      buffers: Buffers
  )(implicit scheduler: Scheduler, cwd: AbsolutePath): SymbolIndexer = {
    val symbols = new SymbolIndexerMap()
    val documents = new InMemoryDocumentStore()

    def indexDocument(document: s.Document): Unit = {
      val uri = URI.create("file:" + cwd.resolve(document.filename))
      documents.putDocument(uri, document)
      document.names.foreach {
        case s.ResolvedName(_, sym, _)
            if !sym.endsWith(".") && !sym.endsWith("#") =>
        // Do nothing, local symbol.
        case s.ResolvedName(Some(s.Position(start, end)), sym, true) =>
          symbols.addDefinition(sym, i.Position(document.filename, start, end))
        case s.ResolvedName(Some(s.Position(start, end)), sym, false) =>
          symbols.addReference(document.filename, i.Range(start, end), sym)
        case _ =>
      }
    }

    val indexer = semanticdbs.map { db =>
      db.documents.foreach(indexDocument)
      Effects.IndexSemanticdb
    }

    new SymbolIndexer(
      indexer,
      symbols,
      documents,
      connection,
      buffers
    )
  }
}

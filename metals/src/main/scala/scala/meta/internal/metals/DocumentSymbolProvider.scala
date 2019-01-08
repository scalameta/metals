package scala.meta.internal.metals

import java.util
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.{lsp4j => l}
import scala.meta._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.transversers.SimpleTraverser

/**
 *  Retrieves all the symbols defined in a document
 *
 *  If the document doesn't parse, we fall back to the latest
 *  known snapshot of the document, if present.
 */
class DocumentSymbolProvider(trees: Trees) {

  def documentSymbols(
      path: AbsolutePath
  ): util.List[DocumentSymbol] = {
    trees.get(path) match {
      case Some(tree) =>
        new SymbolTraverser().symbols(tree)
      case None =>
        List().asJava
    }
  }

  private class SymbolTraverser() extends SimpleTraverser {
    var owner: DocumentSymbol = new DocumentSymbol(
      "root",
      SymbolKind.Namespace,
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      "",
      new util.ArrayList[DocumentSymbol]()
    )
    def symbols(tree: Tree): util.List[DocumentSymbol] = {
      apply(tree)
      owner.getChildren
    }

    def addChild(
        name: String,
        kind: SymbolKind,
        range: Position,
        selection: Position,
        detail: String
    ): Unit = {
      owner.getChildren.add(
        new DocumentSymbol(
          name,
          kind,
          range.toLSP,
          selection.toLSP,
          detail,
          new util.ArrayList[DocumentSymbol]()
        )
      )
    }
    def addPats(
        pats: List[Pat],
        kind: SymbolKind,
        range: Position,
        detail: String
    ): Unit = {
      pats.foreach { pat =>
        pat.collect {
          case Pat.Var(name) =>
            addChild(
              name.value,
              kind,
              range,
              name.pos,
              detail
            )
        }
      }
    }

    override def apply(tree: Tree): Unit = {
      def continue(withNewOwner: Boolean = false): Unit = {
        val oldRoot = owner
        if (withNewOwner) owner = owner.getChildren.asScala.last
        super.apply(tree)
        owner = oldRoot
      }
      def newOwner(): Unit = {
        continue(withNewOwner = true)
      }
      tree match {
        case t: Pkg =>
          addChild(t.ref.syntax, SymbolKind.Package, t.pos, t.ref.pos, "")
          newOwner()
        case _: Source | _: Template =>
          continue()
        case t: Defn.Class =>
          addChild(
            t.name.value,
            SymbolKind.Class,
            t.pos,
            t.name.pos,
            ""
          )
          newOwner()
        case t: Defn.Trait =>
          addChild(
            t.name.value,
            SymbolKind.Interface,
            t.pos,
            t.name.pos,
            ""
          )
          newOwner()
        case t: Defn.Object =>
          addChild(
            t.name.value,
            SymbolKind.Module,
            t.pos,
            t.name.pos,
            ""
          )
          newOwner()
        case t: Pkg.Object =>
          addChild(
            t.name.value,
            SymbolKind.Module,
            t.pos,
            t.name.pos,
            ""
          )
          newOwner()
        case t: Defn.Def =>
          addChild(
            t.name.value,
            SymbolKind.Method,
            t.pos,
            t.name.pos,
            t.decltpe.fold("")(_.syntax)
          )
        case t: Decl.Def =>
          addChild(
            t.name.value,
            SymbolKind.Method,
            t.pos,
            t.name.pos,
            t.decltpe.syntax
          )
        case t: Defn.Val =>
          addPats(
            t.pats,
            SymbolKind.Constant,
            t.pos,
            t.decltpe.fold("")(_.syntax)
          )
        case t: Decl.Val =>
          addPats(
            t.pats,
            SymbolKind.Constant,
            t.pos,
            t.decltpe.syntax
          )
        case t: Defn.Var =>
          addPats(
            t.pats,
            SymbolKind.Variable,
            t.pos,
            t.decltpe.fold("")(_.syntax)
          )
        case t: Decl.Var =>
          addPats(
            t.pats,
            SymbolKind.Variable,
            t.pos,
            t.decltpe.syntax
          )
        case t: Defn.Type =>
          addChild(
            t.name.value,
            SymbolKind.Field,
            t.pos,
            t.name.pos,
            t.body.syntax
          )
        case t: Decl.Type =>
          addChild(
            t.name.value,
            SymbolKind.Field,
            t.pos,
            t.name.pos,
            ""
          )
        case _ =>
      }
    }
  }
}

package scala.meta.internal.metals

import java.net.URI
import java.util

import scala.meta._
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.transversers.SimpleTraverser

import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.{lsp4j => l}

/**
 *  Retrieves all the symbols defined in a document
 *
 *  If the document doesn't parse, we fall back to the latest
 *  known snapshot of the document, if present.
 */
class DocumentSymbolProvider(trees: Trees) {

  def documentSymbols(
      path: URI,
      code: String
  ): util.List[DocumentSymbol] = {
    trees.get(path, code) match {
      case Some(tree) =>
        new SymbolTraverser().symbols(tree)
      case None =>
        List.empty[DocumentSymbol].asJava
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
        val children = owner.getChildren.asScala
        val hasChildren = children.nonEmpty
        if (withNewOwner && hasChildren) owner = children.last
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
        case t: Term.NewAnonymous =>
          val (name, selection) = t.templ.inits match {
            case Nil => ("(anonymous)", t.pos)
            case inits =>
              (inits.map(_.tpe.syntax).mkString(" with "), inits.head.pos)
          }
          if (t.templ.stats.nonEmpty) {
            addChild(s"new $name", SymbolKind.Interface, t.pos, selection, "")
            newOwner()
          } else continue()
        case _: Source | _: Template =>
          continue()
        case block: Term.Block =>
          if (owner.getName() == "try") {
            if (owner.getChildren().isEmpty()) {
              continue()
            } else {
              val blockPos = block.stats.head.pos
              addChild("finally", SymbolKind.Struct, block.pos, block.pos, "")
              newOwner()
            }
          } else {
            continue()
          }
        case t: Case =>
          if (
            owner.getName() == "try" && owner
              .getChildren()
              .asScala
              .forall(_.getName() != "catch")
          )
            addChild("catch", SymbolKind.Struct, t.pos, t.pos, "")
        case t: Term
            if t.isInstanceOf[Term.Try] || t
              .isInstanceOf[Term.TryWithHandler] =>
          if (t.children.nonEmpty) {
            addChild("try", SymbolKind.Struct, t.pos, t.pos, "")
            newOwner()
          } else {
            continue()
          }
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
          newOwner()
        case t: Decl.Def =>
          addChild(
            t.name.value,
            SymbolKind.Method,
            t.pos,
            t.name.pos,
            t.decltpe.syntax
          )
          newOwner()
        case t: Defn.Val =>
          addPats(
            t.pats,
            SymbolKind.Constant,
            t.pos,
            t.decltpe.fold("")(_.syntax)
          )
          newOwner()
        case t: Decl.Val =>
          addPats(
            t.pats,
            SymbolKind.Constant,
            t.pos,
            t.decltpe.syntax
          )
          newOwner()
        case t: Defn.Var =>
          addPats(
            t.pats,
            SymbolKind.Variable,
            t.pos,
            t.decltpe.fold("")(_.syntax)
          )
          newOwner()
        case t: Decl.Var =>
          addPats(
            t.pats,
            SymbolKind.Variable,
            t.pos,
            t.decltpe.syntax
          )
          newOwner()
        case t: Defn.Type =>
          addChild(
            t.name.value,
            SymbolKind.TypeParameter,
            t.pos,
            t.name.pos,
            t.body.syntax
          )
        case t: Decl.Type =>
          addChild(
            t.name.value,
            SymbolKind.TypeParameter,
            t.pos,
            t.name.pos,
            ""
          )
        case _ =>
      }
    }
  }
}

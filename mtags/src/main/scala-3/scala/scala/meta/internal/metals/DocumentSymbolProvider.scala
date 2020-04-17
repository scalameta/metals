package scala.meta.internal.metals

import java.util
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind
import org.eclipse.{lsp4j => l}
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.jdk.CollectionConverters._
import java.net.URI
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.SourceFile
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.parsing.Parsers.Parser
import dotty.tools.dotc.ast.untpd._
import dotty.tools.dotc.printing.Showable
import dotty.tools.dotc.core.{Flags => f}
import java.{util => ju}

/**
 *  Retrieves all the symbols defined in a document
 */
class DocumentSymbolProvider() {

  def documentSymbols()(implicit ctx: Context): util.List[DocumentSymbol] = {
    val tree = ctx.run.units.head.untpdTree
    new SymbolTraverser().symbols(tree)
  }

  class SymbolTraverser extends UntypedTreeTraverser {
    var owner: DocumentSymbol = new DocumentSymbol(
      "root",
      SymbolKind.Namespace,
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      new l.Range(new l.Position(0, 0), new l.Position(0, 0)),
      "",
      new ju.ArrayList[DocumentSymbol]()
    )
    def symbols(
        tree: Tree
    )(implicit ctx: Context): ju.List[DocumentSymbol] = {
      traverse(tree)
      owner.getChildren
    }

    def addChild(
        name: String,
        kind: SymbolKind,
        range: SourcePosition,
        selection: SourcePosition,
        detail: String
    ): Unit = {
      owner.getChildren.add(
        new DocumentSymbol(
          name,
          kind,
          range.toLSP,
          selection.toLSP,
          detail,
          new ju.ArrayList[DocumentSymbol]()
        )
      )
    }
    override def traverse(tree: Tree)(implicit ctx: Context): Unit = {

      def continue(withNewOwner: Boolean = false): Unit = {
        val oldRoot = owner
        val children = owner.getChildren.asScala
        val hasChildren = children.nonEmpty
        if (withNewOwner && hasChildren) owner = children.last
        super.traverseChildren(tree)
        owner = oldRoot
      }

      def newOwner(): Unit = {
        continue(withNewOwner = true)
      }

      def show(s: Showable): String =
        s match {
          case t: Tree if t.isEmpty => ""
          case _ => s.show
        }

      tree match {
        case t: PackageDef =>
          addChild(t.pid.show, SymbolKind.Package, t.sourcePos, t.sourcePos, "")
          newOwner()
        case n @ New(t: Template) =>
          // val (name, selection) = (t.constr, t.parents) match {
          //   case (t, Nil => ("(anonymous)", t.sourcePos)
          //   case inits =>
          //     (inits.map(_.tpe.syntax).mkString(" with "), inits.head.pos)
          // }
          // if (t.templ.stats.nonEmpty) {
          //   addChild(s"new $name", SymbolKind.Interface, t.pos, selection, "")
          //   newOwner()
          // } else continue()
          continue() // TODO(gabro)
        case t: Template =>
          continue()
        case t: Block =>
          if (owner.getName() == "try") {
            if (owner.getChildren().isEmpty()) {
              continue()
            } else {
              val blockPos = t.stats.head.sourcePos
              addChild(
                "finally",
                SymbolKind.Struct,
                t.sourcePos,
                t.sourcePos,
                ""
              )
              newOwner()
            }
          } else {
            continue()
          }
        case t: CaseDef =>
          if (owner.getName() == "try" && owner
              .getChildren()
              .asScala
              .forall(_.getName() != "catch"))
            addChild("catch", SymbolKind.Struct, t.sourcePos, t.sourcePos, "")
        case t: Try =>
          if (t.cases.nonEmpty) {
            addChild("try", SymbolKind.Struct, t.sourcePos, t.sourcePos, "")
            newOwner()
          } else {
            continue()
          }
        case t: TypeDef =>
          val isClass = t.isClassDef
          val isTrait = t.mods.flags.is(f.Trait)
          val isEnum = t.mods.isEnumClass
          val isEnumCase = t.mods.isEnumCase
          val kind =
            if (isClass) SymbolKind.Class
            else if (isTrait) SymbolKind.Interface
            else if (isEnum) SymbolKind.Enum
            else if (isEnumCase) SymbolKind.EnumMember
            else SymbolKind.TypeParameter
          addChild(t.name.show, kind, t.sourcePos, t.sourcePos, "")
          newOwner()
        case t: ModuleDef =>
          addChild(t.name.show, SymbolKind.Module, t.sourcePos, t.sourcePos, "")
          newOwner()
        case t: DefDef
            if !t.isEmpty && !t.name.eq(
              dotty.tools.dotc.core.StdNames.nme.CONSTRUCTOR
            ) =>
          addChild(
            show(t.name),
            SymbolKind.Method,
            t.sourcePos,
            t.sourcePos,
            show(t.tpt)
          )
          newOwner()
        case t: ValDef if !t.isEmpty =>
          val isVar = t.mods.hasMod(classOf[Mod.Var])
          val symbolKind =
            if (isVar) SymbolKind.Variable else SymbolKind.Constant
          addChild(
            show(t.name),
            symbolKind,
            t.sourcePos,
            t.sourcePos,
            show(t.tpt)
          )
          newOwner()
        case _ =>
      }
    }
  }
}

package scala.meta.internal.metals

import org.eclipse.lsp4j.Position
import dotty.tools.dotc.ast.untpd.Tree
import dotty.tools.dotc.ast.untpd.PackageDef
import dotty.tools.dotc.ast.untpd.TypeDef
import dotty.tools.dotc.ast.untpd.DefDef
import dotty.tools.dotc.ast.untpd.ModuleDef
import dotty.tools.dotc.ast.untpd.Template
import dotty.tools.dotc.ast.untpd.UntypedTreeTraverser
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.core.Contexts.Context
import scala.meta.internal.mtags.MtagsEnrichments._
import dotty.tools.dotc.core.Flags

object ClassFinder {

  class SymbolTraverser(offset: Int, fileName: String)
      extends UntypedTreeTraverser {
    var symbol = ""
    var isInnerClass: Boolean = false
    var isToplevel: Boolean = true
    override def traverse(tree: Tree)(implicit ctx: Context): Unit = {
      if (
        tree.sourcePos.exists && tree.sourcePos.start <= offset && offset <= tree.sourcePos.end
      ) {
        val delimeter =
          if (symbol.endsWith("$")) ""
          else if (isInnerClass) "$"
          else if (symbol.isEmpty) ""
          else "."

        tree match {
          case pkg: PackageDef if !pkg.symbol.isEmptyPackage =>
            val name = pkg._1.show
            symbol = symbol + name
            super.traverseChildren(tree)

          case _: PackageDef =>
            super.traverseChildren(tree)

          case obj: ModuleDef if obj.mods.is(Flags.Package) =>
            val prefix = if (symbol.isEmpty()) "" else "."
            val name = obj.name.show
            symbol = symbol + prefix + name + ".package" + "$"
            isInnerClass = true
            isToplevel = false
            super.traverseChildren(tree)

          case obj: ModuleDef =>
            val name = obj.name.show
            symbol = symbol + delimeter + name + "$"
            isInnerClass = true
            isToplevel = false
            super.traverseChildren(tree)

          case cls: TypeDef =>
            val name = cls.name.show
            symbol = symbol + delimeter + name
            isInnerClass = true
            isToplevel = false
            super.traverseChildren(tree)

          case method: DefDef if isToplevel =>
            symbol = symbol + delimeter + fileName + "$package"
            isInnerClass = true
            isToplevel = false

          case _: Template =>
            super.traverseChildren(tree)

          case _ =>
        }
      }
    }

    def find(tree: Tree)(implicit ctx: Context): String = {
      traverse(tree)
      symbol
    }
  }

  def findClassForOffset(
      offset: Int,
      fileName: String
  )(implicit ctx: Context): String = {
    val tree = ctx.run.units.head.untpdTree
    new SymbolTraverser(offset, fileName).find(tree)
  }
}

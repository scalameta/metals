package scala.meta.internal.metals

import org.eclipse.lsp4j.Position
import dotty.tools.dotc.ast.untpd.Tree
import dotty.tools.dotc.ast.untpd.PackageDef
import dotty.tools.dotc.ast.untpd.TypeDef
import dotty.tools.dotc.ast.untpd.ModuleDef
import dotty.tools.dotc.ast.untpd.UntypedTreeTraverser
import dotty.tools.dotc.ast.untpd
import dotty.tools.dotc.util.SourcePosition
import dotty.tools.dotc.core.Contexts.Context
import scala.meta.internal.mtags.MtagsEnrichments._
import dotty.tools.dotc.core.Flags

object ClassFinder {

  class SymbolTraverser(pos: Position) extends UntypedTreeTraverser {
    var symbol = ""
    var isInnerClass: Boolean = false
    override def traverse(tree: Tree)(implicit ctx: Context): Unit = {

      if (tree.sourcePos.toLSP.encloses(pos)) {
        val delimeter =
          if (symbol.endsWith("$")) ""
          else if (isInnerClass) "$"
          else if (symbol.isEmpty) ""
          else "."

        tree match {
          case pkg: PackageDef if !pkg.symbol.isEmptyPackage =>
            val name = pkg._1.show
            symbol = symbol + name

          case obj: ModuleDef if obj.mods.is(Flags.Package) =>
            val prefix = if (symbol.isEmpty()) "" else "."
            val name = obj.name.show
            symbol = symbol + prefix + name + ".package" + "$"
            isInnerClass = true

          case obj: ModuleDef =>
            val name = obj.name.show
            symbol = symbol + delimeter + name + "$"
            isInnerClass = true

          case cls: TypeDef =>
            val name = cls.name.show
            symbol = symbol + delimeter + name
            isInnerClass = true
          case _ =>
        }
        super.traverseChildren(tree)
      }
    }

    def find(tree: Tree)(implicit ctx: Context): String = {
      traverse(tree)
      symbol
    }
  }

  def findClassForPos(
      pos: Position
  )(implicit ctx: Context): String = {
    val tree = ctx.run.units.head.untpdTree
    new SymbolTraverser(pos).find(tree)
  }
}

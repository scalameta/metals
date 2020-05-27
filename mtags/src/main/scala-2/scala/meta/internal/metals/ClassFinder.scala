package scala.meta.internal.metals

import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Tree
import scala.meta.internal.mtags.MtagsEnrichments._

import org.eclipse.lsp4j.Position

object ClassFinder {

  def findClassForPos(
      tree: Tree,
      pos: Position,
      symbol: String = "",
      isInnerClass: Boolean = false
  ): String = {
    val delimeter =
      if (symbol.endsWith("$")) ""
      else if (isInnerClass) "$"
      else if (symbol.isEmpty()) ""
      else "."

    val (fullName, isInner) = tree match {
      case Pkg(ref, _) =>
        val name = ref.toString()
        (symbol + delimeter + name, false)

      case obj: Pkg.Object =>
        val prefix = if (symbol.isEmpty()) "" else "."
        val name = obj.name.toString()
        (symbol + prefix + name + ".package" + "$", true)

      case obj: Defn.Object =>
        val name = obj.name.toString()
        (symbol + delimeter + name + "$", true)

      case cls: Defn.Class =>
        val name = cls.name.toString()
        (symbol + delimeter + name, true)

      case trt: Defn.Trait =>
        val name = trt.name.toString()
        (symbol + delimeter + name, true)

      case _ =>
        (symbol, isInnerClass)
    }

    tree.children.find { child => child.pos.toLSP.encloses(pos) } match {
      case None => fullName
      case Some(value) => findClassForPos(value, pos, fullName, isInner)
    }

  }
}

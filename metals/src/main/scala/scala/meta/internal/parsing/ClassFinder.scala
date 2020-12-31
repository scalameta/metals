package scala.meta.internal.parsing

import scala.meta.Defn
import scala.meta.Pkg
import scala.meta.Tree
import scala.meta.inputs.Position
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

class ClassFinder(trees: Trees) {

  def findClass(path: AbsolutePath, pos: l.Position): Option[String] = {
    for {
      tree <- trees.get(path)
    } yield {
      val input = tree.pos.input
      val metaPos = pos.toMeta(input)
      findClassForOffset(tree, metaPos, path.filename)
    }
  }

  private def findClassForOffset(
      tree: Tree,
      pos: Position,
      fileName: String,
      symbol: String = "",
      isInsideClass: Boolean = false
  ): String = {
    val delimeter =
      if (symbol.endsWith("$")) ""
      else if (isInsideClass) "$"
      else if (symbol.isEmpty()) ""
      else "."

    val (fullName, isInner) = tree match {
      // toplevel Scala3 definition, generated class is `<filename>$package`
      case _: Defn.Def if !isInsideClass =>
        (
          symbol + delimeter + fileName.stripSuffix(".scala") + "$package",
          false
        )

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
        (symbol, isInsideClass)
    }

    // Scala 3 outer methods should just return `filename$package`
    // which does not work in case of normal classes
    val shouldNotContinue = tree.is[Defn.Def] && !isInsideClass
    if (shouldNotContinue) {
      fullName
    } else {
      tree.children.find { child =>
        child.pos.start < pos.start && pos.start < child.pos.end
      } match {
        case None => fullName
        case Some(value) =>
          findClassForOffset(value, pos, fileName, fullName, isInner)
      }
    }
  }
}

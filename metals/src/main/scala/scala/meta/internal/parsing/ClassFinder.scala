package scala.meta.internal.parsing

import scala.annotation.tailrec
import scala.collection.mutable

import scala.meta.Defn
import scala.meta.Member
import scala.meta.Pkg
import scala.meta.Position
import scala.meta.Tree
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final case class ClassWithPos(
    path: String,
    friendlyName: String,
    description: String
)

class ClassFinder(trees: Trees) {

  def findClass(path: AbsolutePath, pos: l.Position): Option[String] =
    findClass(path, pos, checkInnerClasses = true)

  def findTasty(path: AbsolutePath, pos: l.Position): Option[String] =
    findClass(path, pos, checkInnerClasses = false)
      .map(_.stripSuffix("$"))

  def findAllClasses(
      path: AbsolutePath,
      checkInnerClasses: Boolean
  ): Option[List[ClassWithPos]] =
    for {
      tree <- trees.get(path)
    } yield {
      val extension = if (checkInnerClasses) "class" else "tasty"
      val definitions = new mutable.ArrayBuffer[ClassWithPos]()
      var isToplevelAdded: Boolean = false

      def name(tree: Tree) = tree match {
        case cls: Defn.Class => s"Class ${cls.name.value}"
        case cls: Defn.Object => s"Object ${cls.name.value}"
        case cls: Defn.Trait => s"Trait ${cls.name.value}"
        case cls: Defn.Enum => s"Enum ${cls.name.value}"
        case _: Defn.Def => "Toplevel package"
      }

      def addDfn(dfn: Member, isInnerClass: Boolean = false): Unit = {
        val suffixToStrip = if (isInnerClass || !checkInnerClasses) "$" else ""
        val classWithPackage =
          findClassForOffset(tree, dfn.pos, path.filename, checkInnerClasses)
            .stripSuffix(suffixToStrip)
        val resourceDir = classWithPackage.replace('.', '/')
        val suffix =
          if (isInnerClass) s"$$${dfn.name.value}.$extension"
          else s".$extension"
        val resourcePath = s"$resourceDir$suffix"
        val description = s"$classWithPackage$suffix"
        val c = ClassWithPos(resourcePath, name(dfn), description)
        definitions.append(c)
      }

      def loop(tree: Tree, isInnerClass: Boolean = false): Unit = tree match {
        case _: Pkg | _: Pkg.Object =>
          tree.children.foreach(loop(_, isInnerClass))
        case _: Defn.Class | _: Defn.Trait | _: Defn.Object | _: Defn.Enum =>
          addDfn(tree.asInstanceOf[Member], isInnerClass)
          if (checkInnerClasses)
            tree.children.foreach(loop(_, isInnerClass = true))
        case dfn: Defn.Def if !isInnerClass && !isToplevelAdded =>
          isToplevelAdded = true
          addDfn(dfn)
        case _: Defn.Def => ()
        case _ =>
          tree.children.foreach(loop(_, isInnerClass))
      }
      loop(tree)
      definitions.toList.distinctBy(_.path)
    }

  private def findClass(
      path: AbsolutePath,
      pos: l.Position,
      checkInnerClasses: Boolean
  ): Option[String] = trees
    .get(path)
    .map { tree =>
      val input = tree.pos.input
      val metaPos = pos.toMeta(input)
      findClassForOffset(tree, metaPos, path.filename, checkInnerClasses)
    }
    .filter(_.nonEmpty)

  private def findClassForOffset(
      tree: Tree,
      pos: Position,
      fileName: String,
      inspectInnerClasses: Boolean
  ): String = {
    @tailrec
    def loop(tree: Tree, symbol: String, isInsideClass: Boolean): String = {
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

        case enm: Defn.Enum =>
          val name = enm.name.toString()
          (symbol + delimeter + name + "$", true)

        case _ =>
          (symbol, isInsideClass)
      }

      // Scala 3 outer methods should just return `filename$package`
      // which does not work in case of normal classes
      val shouldNotContinue =
        (tree.is[Defn.Def] && !isInsideClass) ||
          (!inspectInnerClasses && isInsideClass)
      if (shouldNotContinue) {
        fullName
      } else {
        tree.children.find { child =>
          child.pos.start <= pos.start && pos.start <= child.pos.end
        } match {
          case None => fullName
          case Some(t) =>
            loop(t, fullName, isInner)
        }
      }
    }

    loop(tree, "", false)
  }

}

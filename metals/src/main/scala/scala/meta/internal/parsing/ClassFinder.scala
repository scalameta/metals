package scala.meta.internal.parsing

import scala.annotation.tailrec
import scala.collection.mutable

import scala.meta.Defn
import scala.meta.Member
import scala.meta.Mod
import scala.meta.Pkg
import scala.meta.Position
import scala.meta.Self
import scala.meta.Tree
import scala.meta.Type
import scala.meta.given
import scala.meta.internal.metals.MetalsEnrichments.given
import scala.meta.internal.parsing.finder.ClassKind
import scala.meta.internal.parsing.finder.MangledClassName
import scala.meta.internal.parsing.finder.ShortClassName
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final case class ClassArtifact(
    kind: ClassKind,
    mangledClassName: MangledClassName,
    shortClassName: ShortClassName,
    extensionSuffix: String,
) {
  private val resourceDir = mangledClassName.value.replace('.', '/')

  val resourcePath: String = s"$resourceDir$extensionSuffix"
  val resourceMangledName: String = s"${mangledClassName.value}$extensionSuffix"
  val prettyName: String = kind match {
    case ClassKind.Class => s"Class ${shortClassName.value}"
    case ClassKind.Trait => s"Trait ${shortClassName.value}"
    case ClassKind.Object => s"Object ${shortClassName.value}"
    case ClassKind.Enum => s"Enum ${shortClassName.value}"
    case ClassKind.ToplevelPackage => "Toplevel package"
  }
}

class ClassFinder(trees: Trees) {

  def findClass(path: AbsolutePath, pos: l.Position): Option[MangledClassName] =
    findClass(path, pos, searchGranularity = ClassFinderGranularity.ClassFiles)

  def findTasty(path: AbsolutePath, pos: l.Position): Option[MangledClassName] =
    findClass(path, pos, searchGranularity = ClassFinderGranularity.Tasty)
      .map(_.stripSuffix("$"))

  def findAllClasses(
      path: AbsolutePath,
      searchGranularity: ClassFinderGranularity,
  ): Option[Vector[ClassArtifact]] =
    for {
      tree <- trees.get(path)
    } yield {
      val definitions = new mutable.ArrayBuffer[ClassArtifact]()
      var isToplevelAdded: Boolean = false

      def addDfnToResults(dfn: Member): Unit = {
        val suffixToStrip =
          if (searchGranularity.isTasty) "$" else ""

        val mangledName = findClassNameForOffset(
          tree,
          dfn.pos,
          path.filename,
          searchGranularity,
        ).map(_.stripSuffix(suffixToStrip)).getOrElse(MangledClassName(""))

        val (shortName, defnKind) = dfn match {
          case cls: Defn.Class =>
            ShortClassName(cls.name.value) -> ClassKind.Class
          case cls: Defn.Object =>
            ShortClassName(cls.name.value) -> ClassKind.Object
          case cls: Defn.Trait =>
            ShortClassName(cls.name.value) -> ClassKind.Trait
          case cls: Defn.Enum =>
            ShortClassName(cls.name.value) -> ClassKind.Enum
          case _: Defn.Def =>
            ShortClassName("") -> ClassKind.ToplevelPackage
        }

        val classEntry = ClassArtifact(
          defnKind,
          mangledName,
          shortName,
          searchGranularity.extension,
        )
        definitions.append(classEntry)

        // implicits classes which extends AnyVal produce also companion object which hols
        // defined extension methods, add such object to results
        dfn match {
          case cls: Defn.Class if addCompanionObject(cls) =>
            val companionObject = ClassArtifact(
              ClassKind.Object,
              MangledClassName(mangledName.value + "$"),
              shortName,
              searchGranularity.extension,
            )
            // in case when companion object is defined explicitly,
            // duplicate entry will be discarded at the end by distinctBy
            definitions.append(companionObject)
          case _ => ()
        }
      }

      def loop(tree: Tree, isInnerClass: Boolean = false): Unit = tree match {
        case _: Pkg | _: Pkg.Object =>
          tree.children.foreach(loop(_, isInnerClass))

        case _: Defn.Class | _: Defn.Trait | _: Defn.Object | _: Defn.Enum =>
          addDfnToResults(tree.asInstanceOf[Member])
          if (!searchGranularity.isTasty)
            tree.children.foreach(loop(_, isInnerClass = true))

        case dfn: Defn.Def if !isInnerClass && !isToplevelAdded =>
          isToplevelAdded = true
          addDfnToResults(dfn)

        case _: Defn.Def => ()

        case _ =>
          tree.children.foreach(loop(_, isInnerClass))
      }

      loop(tree)
      definitions.toVector.distinctBy(_.resourcePath)
    }

  private def findClass(
      path: AbsolutePath,
      pos: l.Position,
      searchGranularity: ClassFinderGranularity,
  ): Option[MangledClassName] = {
    for {
      tree <- trees.get(path)
      input = tree.pos.input
      metaPos <- pos.toMeta(input)
      cls <- findClassNameForOffset(
        tree,
        metaPos,
        path.filename,
        searchGranularity,
      )
    } yield cls
  }

  private def findClassNameForOffset(
      tree: Tree,
      pos: Position,
      fileName: String,
      searchGranularity: ClassFinderGranularity,
  ): Option[MangledClassName] = {
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
            false,
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
          (searchGranularity.isTasty && isInsideClass)
      if (shouldNotContinue) {
        fullName
      } else {
        tree.children.find {
          case Self(_, _) =>
            // don't go into the self-type declaration (which
            // exists even if it's empty, in which case it will
            // have a matching position):
            false
          case child =>
            child.pos.start <= pos.start && pos.start <= child.pos.end
        } match {
          case None => fullName
          case Some(t) =>
            loop(t, fullName, isInner)
        }
      }
    }

    val name = loop(tree, "", false)

    if (name.isEmpty) None
    else Some(MangledClassName(name))
  }

  private def addCompanionObject(cls: Defn.Class): Boolean =
    isImplicitAnyValDefinition(cls) || isCaseClassDefinition(cls)

  private def isImplicitAnyValDefinition(cls: Defn.Class): Boolean = {
    cls.mods.exists(_.is[Mod.Implicit]) &&
    cls.templ.inits.exists(_.tpe match {
      case Type.Name("AnyVal") => true
      case _: Type => false
    })
  }

  private def isCaseClassDefinition(cls: Defn.Class): Boolean =
    cls.mods.exists(_.is[Mod.Case])

}

sealed trait ClassFinderGranularity {
  import ClassFinderGranularity._

  def isTasty: Boolean = this match {
    case ClassFiles => false
    case Tasty => true
  }

  def extension: String = this match {
    case ClassFiles => ".class"
    case Tasty => ".tasty"
  }
}

object ClassFinderGranularity {
  case object ClassFiles extends ClassFinderGranularity
  case object Tasty extends ClassFinderGranularity
}

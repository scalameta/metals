package scala.meta.internal.jpc

import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.element.Element
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement

import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/**
 * Renders Java types using simple names where it is safe to do so, collecting
 * the imports that need to be added for the shortened names to resolve.
 *
 * Extends [[JavaTypeVisitor]] (which renders fully qualified names) and only
 * overrides how declared types are rendered; the recursion over arrays,
 * wildcards, intersections, type arguments, etc. is inherited and routes back
 * through [[shorten]], so nested types are shortened too.
 *
 * Nested member types are rendered by importing the outermost enclosing
 * top-level type and qualifying the rest with simple names (for example
 * `java.util.Map.Entry` becomes `Map.Entry` with `import java.util.Map`).
 *
 * A type is kept fully qualified when shortening would be unsafe: simple names
 * that clash with an existing import or a type declared in the file, and simple
 * names already claimed by a different fully qualified name in the generated
 * code.
 */
class JavaTypeShortener(
    currentPackage: String,
    existingImports: Map[String, String],
    declaredTypeNames: Set[String]
) extends JavaTypeVisitor {
  // simpleName -> fully qualified name that the simple name currently resolves to
  private val claimed = mutable.Map.empty[String, String] ++ existingImports
  private val collected = mutable.LinkedHashSet.empty[String]

  /** The imports that need to be added, sorted. */
  def newImports: List[String] = collected.toList.sorted

  def shorten(tpe: TypeMirror): String = visit(tpe)

  override def visitDeclared(t: DeclaredType, p: Void): String =
    t.asElement() match {
      case element: TypeElement =>
        val typeArguments = t.getTypeArguments()
        val args =
          if (typeArguments.isEmpty) ""
          else
            typeArguments.asScala
              .map(arg => visit(arg))
              .mkString("<", ", ", ">")
        s"${shortenName(element)}$args"
      case _ => super.visitDeclared(t, p)
    }

  private def shortenName(element: TypeElement): String =
    if (element.getNestingKind() == NestingKind.TOP_LEVEL)
      shortenTopLevel(element)
    else
      enclosingTopLevel(element) match {
        case Some(outer) =>
          // Import the outermost type and qualify the nested path by simple
          // names, e.g. `java.util.Map.Entry` -> `Map.Entry`.
          shortenTopLevel(outer) + nestedTail(element, outer)
        case None =>
          // Local or anonymous enclosing type that cannot be imported.
          element.getQualifiedName().toString()
      }

  private def shortenTopLevel(element: TypeElement): String = {
    val fqn = element.getQualifiedName().toString()
    val simpleName = element.getSimpleName().toString()
    val pkg =
      if (fqn.contains(".")) fqn.substring(0, fqn.lastIndexOf('.')) else ""
    if (pkg == "java.lang" || pkg == currentPackage) {
      // Accessible without an import, but still claim the name so that a
      // later type with the same simple name stays fully qualified.
      claimed.getOrElseUpdate(simpleName, fqn)
      if (claimed(simpleName) == fqn) simpleName else fqn
    } else
      claimed.get(simpleName) match {
        case Some(claimedFqn) =>
          if (claimedFqn == fqn) simpleName else fqn
        case None =>
          if (declaredTypeNames.contains(simpleName)) fqn
          else {
            claimed(simpleName) = fqn
            collected += fqn
            simpleName
          }
      }
  }

  @tailrec
  private def enclosingTopLevel(
      element: TypeElement
  ): Option[TypeElement] =
    if (element.getNestingKind() == NestingKind.TOP_LEVEL) Some(element)
    else
      element.getEnclosingElement() match {
        case parent: TypeElement => enclosingTopLevel(parent)
        case _ => None
      }

  /** The `.`-prefixed path of simple names from `outer` (exclusive) to `element`. */
  private def nestedTail(element: TypeElement, outer: TypeElement): String = {
    @tailrec
    def loop(current: Element, acc: List[String]): List[String] =
      if (current == outer) acc
      else
        current match {
          case typeElement: TypeElement =>
            loop(
              typeElement.getEnclosingElement(),
              typeElement.getSimpleName().toString() :: acc
            )
          case _ => acc
        }
    loop(element, Nil) match {
      case Nil => ""
      case names => names.mkString(".", ".", "")
    }
  }
}

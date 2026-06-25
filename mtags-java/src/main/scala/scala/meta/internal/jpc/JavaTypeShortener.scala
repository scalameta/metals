package scala.meta.internal.jpc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.IntersectionType
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.`type`.UnionType
import javax.lang.model.`type`.WildcardType
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
    onDemandPackages: Set[String],
    declaredTypeNames: Set[String]
) {
  // simpleName -> fully qualified name that the simple name currently resolves to
  private val claimed = mutable.Map.empty[String, String] ++ existingImports
  private val collected = mutable.LinkedHashSet.empty[String]

  /** The imports that need to be added, sorted. */
  def newImports: List[String] = collected.toList.sorted

  def shorten(tpe: TypeMirror): String =
    tpe match {
      case array: ArrayType => s"${shorten(array.getComponentType())}[]"
      case declared: DeclaredType => shortenDeclared(declared)
      case wildcard: WildcardType => shortenWildcard(wildcard)
      case typeVar: TypeVariable =>
        typeVar.asElement().getSimpleName().toString()
      case intersection: IntersectionType =>
        intersection.getBounds().asScala.map(shorten).mkString(" & ")
      case union: UnionType =>
        union.getAlternatives().asScala.map(shorten).mkString(" | ")
      case other => other.toString()
    }

  private def shortenDeclared(declared: DeclaredType): String = {
    val base = declared.asElement() match {
      case element: TypeElement => shortenName(element)
      case _ => declared.toString()
    }
    val typeArgs = declared.getTypeArguments().asScala
    val args =
      if (typeArgs.isEmpty) ""
      else typeArgs.map(shorten).mkString("<", ", ", ">")
    s"$base$args"
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
            if (!onDemandPackages.contains(pkg)) collected += fqn
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

  private def shortenWildcard(wildcard: WildcardType): String = {
    val superBound = wildcard.getSuperBound()
    val extendsBound = wildcard.getExtendsBound()
    if (superBound != null) s"? super ${shorten(superBound)}"
    else if (extendsBound != null) s"? extends ${shorten(extendsBound)}"
    else "?"
  }
}

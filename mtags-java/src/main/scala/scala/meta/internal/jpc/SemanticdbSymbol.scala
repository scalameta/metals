package scala.meta.internal.jpc

import javax.lang.model.element.Element
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.element.VariableElement

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

object SemanticdbSymbol {
  val EmptySymbol: String = ""
  val RootPackageSymbol: String = "_root_/"

  // TODO: replace the logic from scip-java, which is more comprehensive and
  // handles locals as well.
  def fromElement(element: Element): String = {
    @tailrec
    def descriptors(
        acc: List[Descriptor],
        element: Element
    ): List[Descriptor] = {
      if (element == null || element.getSimpleName.toString == "") {
        if (acc.isEmpty) Empty :: Nil
        else acc
      } else if (isLocalVariable(element)) {
        // TODO: We should replace this entire method with
        // GlobalSymbolsCache.semanticdbSymbol(), which is more spec-compliant
        // and handles locals.
        return Local("UNKNOWN_BECAUSE_WE_HAVENT_HANDLED_LOCALS_YET") :: Nil
      } else {
        val elements = {
          element match {
            case packageElement: PackageElement =>
              packageElement.getQualifiedName.toString
                .split('.')
                .map(Package(_))
                .toList
            case executableElement: ExecutableElement =>
              List(
                Method(
                  executableElement.getSimpleName().toString(),
                  disambiguator(executableElement)
                )
              )
            case typeElement: TypeElement =>
              List(Class(typeElement.getSimpleName().toString()))
            case typeParameterElement: TypeParameterElement =>
              List(
                TypeVariable(typeParameterElement.getSimpleName().toString())
              )
            case variableElement: VariableElement =>
              List(Var(variableElement.getSimpleName().toString()))
            case _ => List(Empty)
          }
        }

        descriptors(elements ::: acc, element.getEnclosingElement())
      }
    }

    val decs = descriptors(Nil, element).filter(_ != Empty)

    (decs match {
      case Nil => List.empty[Descriptor]
      case d @ (Local(_) :: _) => d
      case d @ (Package(_) :: _) => d
      case d => Package("_empty_") :: d
    }).mkString("")
  }

  private def isLocalVariable(sym: Element): Boolean = {
    import javax.lang.model.element.ElementKind._
    sym.getKind() match {
      case PARAMETER | EXCEPTION_PARAMETER | LOCAL_VARIABLE =>
        true
      case _ =>
        false
    }
  }

  private def disambiguator(executableElement: ExecutableElement): String = {
    val elements = executableElement.getEnclosingElement.getEnclosedElements
    val methods = elements.asScala.collect {
      case e: ExecutableElement
          if e.getSimpleName == executableElement.getSimpleName =>
        e
    }.toBuffer
    val sortedMethods = methods.sortBy(_.getReceiverType() == null)
    val index = sortedMethods.zipWithIndex.collectFirst {
      case (e, i) if e.equals(executableElement) => i
    }

    index match {
      case Some(i) => if (i == 0) "()" else s"(+$i)"
      case scala.None => "()"
    }
  }

  /**
   * Computes the method "disambiguator" according to the SemanticDB spec.
   *
   * <p><quote> Concatenation of a left parenthesis ("("), a tag and a right parenthesis (")"). If
   * the definition is not overloaded, the tag is empty. If the definition is overloaded, the tag is
   * computed depending on where the definition appears in the following order:
   *
   * <ul>
   *   <li>non-static overloads first, following the same order as they appear in the original
   *       source,
   *   <li>static overloads secondly, following the same order as they appear in the original source
   * </ul>
   *
   * </quote>
   *
   * <p><a href="https://scalameta.org/docs/semanticdb/specification.html#symbol-2">Link to
   * SemanticDB spec</a>.
   */

}

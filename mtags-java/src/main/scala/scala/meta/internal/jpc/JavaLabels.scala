package scala.meta.internal.jpc

import javax.lang.model.`type`.TypeMirror
import javax.lang.model.element.ElementKind.CONSTRUCTOR
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.VariableElement

import scala.jdk.CollectionConverters.CollectionHasAsScala

object JavaLabels {
  def typeLabel(t: TypeMirror): String =
    t.accept(new JavaTypeVisitor(), null)

  def argumentLabel(v: VariableElement): String = {
    val argType = typeLabel(v.asType())
    val argName = v.getSimpleName

    s"$argType $argName"
  }

  def argumentsLabel(e: ExecutableElement): String =
    e.getParameters.asScala.map(argumentLabel).mkString(", ")

  def executableName(e: ExecutableElement): String =
    (if (e.getKind == CONSTRUCTOR)
       e.getEnclosingElement.getSimpleName
     else e.getSimpleName).toString

  def executableLabel(e: ExecutableElement): String =
    s"${executableName(e)}(${argumentsLabel(e)})"

}

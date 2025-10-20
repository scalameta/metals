package scala.meta.internal.jpc

import javax.lang.model.`type`.TypeMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind.ANNOTATION_TYPE
import javax.lang.model.element.ElementKind.CLASS
import javax.lang.model.element.ElementKind.CONSTRUCTOR
import javax.lang.model.element.ElementKind.ENUM
import javax.lang.model.element.ElementKind.INTERFACE
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.PackageElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement

import scala.jdk.CollectionConverters.CollectionHasAsScala

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.ContentType
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import com.sun.source.util.Trees

class JavaHoverProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams,
    contentType: ContentType
) {

  def hover(): Option[HoverSignature] = params match {
    case range: RangeParams => range.trimWhitespaceInRange.flatMap(hoverOffset)
    case _ if isWhitespace => None
    case _ => hoverOffset(params)
  }

  private def isWhitespace: Boolean = {
    params.offset() < 0 ||
    params.offset() >= params.text().length ||
    params.text().charAt(params.offset()).isWhitespace
  }

  private def hoverOffset(params: OffsetParams): Option[HoverSignature] = {
    for {
      (task, node) <- compiler.nodeAtPosition(params)
      element = Trees.instance(task.task).getElement(node)
      docs =
        if (compiler.metalsConfig.isHoverDocumentationEnabled)
          compiler.documentation(task.task, element)
        else ""
      hover <- hoverType(element, docs)
    } yield hover
  }

  private def hoverType(
      element: Element,
      docs: String
  ): Option[HoverSignature] = {
    val sig = element match {
      case e: VariableElement => Some(variableHover(e))
      case e: TypeElement => Some(classHover(e))
      case e: ExecutableElement => Some(executableHover(e))
      case e: PackageElement => Some(packageHover(e))
      case _ => None
    }

    sig.map(s =>
      JavaHover(
        symbolSignature = Some(s),
        docstring = Some(docs),
        contentType = contentType
      )
    )
  }

  private def typeHover(t: TypeMirror): String =
    t.accept(new JavaTypeVisitor(), null)

  private def modifiersHover(
      element: Element,
      filter: Set[Modifier] = Set()
  ): String = {
    val modifiers =
      element.getModifiers.asScala.filterNot(m => filter.contains(m))
    if (modifiers.isEmpty) "" else modifiers.mkString("", " ", " ")
  }

  private def classHover(element: TypeElement): String = {
    val (typeKind, fModifiers) = element.getKind match {
      case CLASS => ("class", Set.empty[Modifier])
      case INTERFACE => ("interface", Set(Modifier.ABSTRACT))
      case ENUM => ("enum", Set.empty[Modifier])
      case ANNOTATION_TYPE => ("@interface", Set.empty[Modifier])
      case _ => ("", Set.empty[Modifier])
    }

    val modifiers = modifiersHover(element, fModifiers)

    val name = typeHover(element.asType())
    val superClass = typeHover(element.getSuperclass) match {
      case sC if sC == "java.lang.Object" || sC == "none" => ""
      case sC => s" extends $sC"
    }

    val implementedClasses = element.getInterfaces.asScala.map(typeHover)
    val implementedClassesHover =
      if (implementedClasses.isEmpty) ""
      else if (element.getKind == INTERFACE)
        implementedClasses.mkString(" extends ", ", ", "")
      else implementedClasses.mkString(" implements ", ", ", "")

    s"$modifiers$typeKind $name$superClass$implementedClassesHover"
  }

  private def argumentHover(element: VariableElement): String = {
    val argType = typeHover(element.asType())
    val argName = element.getSimpleName

    s"$argType $argName"
  }

  private def executableHover(element: ExecutableElement): String = {
    val modifiers = modifiersHover(element)

    val (returnType, functionName) =
      if (element.getKind == CONSTRUCTOR)
        ("", element.getEnclosingElement.getSimpleName)
      else (typeHover(element.asType()), element.getSimpleName)

    val arguments =
      element.getParameters.asScala.map(argumentHover).mkString(", ")

    val throws = element.getThrownTypes.asScala
    val throwsHover =
      if (throws.isEmpty) ""
      else
        throws
          .map(t => t.accept(new JavaTypeVisitor(), null))
          .mkString(" throws ", ", ", "")

    s"$modifiers$returnType $functionName($arguments)$throwsHover".replaceAll(
      " +",
      " "
    )
  }

  private def packageHover(element: PackageElement): String =
    s"package ${element.getQualifiedName}"

  private def variableHover(element: VariableElement): String = {
    val modifiers = modifiersHover(element)
    val variableType = typeHover(element.asType())
    val name = element.getSimpleName

    s"$modifiers$variableType $name"
  }

}

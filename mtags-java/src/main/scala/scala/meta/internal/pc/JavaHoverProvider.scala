package scala.meta.internal.pc

import java.util
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
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.element.VariableElement
import javax.lang.model.util.Elements
import javax.lang.model.util.Types

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava
import scala.jdk.OptionConverters.RichOptional

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.HoverSignature
import scala.meta.pc.OffsetParams
import scala.meta.pc.ParentSymbols
import scala.meta.pc.RangeParams

import com.sun.source.util.JavacTask
import com.sun.source.util.Trees

class JavaHoverProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams
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

  def hoverOffset(params: OffsetParams): Option[HoverSignature] = {
    val task: JavacTask =
      compiler.compilationTask(params.text(), params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val types = task.getTypes()
    val elements = task.getElements()
    val position = params match {
      case p: RangeParams =>
        CursorPosition(p.offset(), p.offset(), p.endOffset())
      case p: OffsetParams => CursorPosition(p.offset(), p.offset(), p.offset())
    }

    val node = compiler.compilerTreeNode(scanner, position)

    for {
      n <- node
      element = Trees.instance(task).getElement(n)
      docs =
        if (compiler.metalsConfig.isHoverDocumentationEnabled)
          documentation(element, types, elements)
        else ""
      hover <- hoverType(element, docs)
    } yield hover
  }

  def hoverType(element: Element, docs: String): Option[HoverSignature] = {
    val sig = element match {
      case e: VariableElement => Some(variableHover(e))
      case e: TypeElement => Some(classHover(e))
      case e: ExecutableElement => Some(executableHover(e))
      case e: PackageElement => Some(packageHover(e))
      case _ => None
    }

    sig.map(s => JavaHover(symbolSignature = Some(s), docstring = Some(docs)))
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

  private def documentation(
      element: Element,
      types: Types,
      elements: Elements
  ): String = {
    val sym = semanticdbSymbol(element)
    compiler.search
      .documentation(
        sym,
        new ParentSymbols {
          override def parents(): util.List[String] = {
            element match {
              case executableElement: ExecutableElement =>
                element.getEnclosingElement match {
                  case enclosingElement: TypeElement =>
                    overriddenSymbols(
                      executableElement,
                      enclosingElement,
                      types,
                      elements
                    )
                  case _ => util.Collections.emptyList[String]
                }
              case _ => util.Collections.emptyList[String]
            }
          }
        }
      )
      .toScala
      .map(_.docstring())
      .getOrElse("")
  }

  private def overriddenSymbols(
      executableElement: ExecutableElement,
      enclosingElement: TypeElement,
      types: Types,
      elements: Elements
  ): util.List[String] = {
    val overriddenSymbols = for {
      // get superclasses
      superType <- types.directSupertypes(enclosingElement.asType()).asScala
      superElement = types.asElement(superType)
      // get elements of superclass
      enclosedElement <- superElement match {
        case typeElement: TypeElement =>
          typeElement.getEnclosedElements().asScala
        case _ => Nil
      }
      // filter out non-methods
      enclosedExecutableElement <- enclosedElement match {
        case enclosedExecutableElement: ExecutableElement =>
          Some(enclosedExecutableElement)
        case _ => None
      }
      // check super method overrides original method
      if (elements.overrides(
        executableElement,
        enclosedExecutableElement,
        enclosingElement
      ))
      symbol = semanticdbSymbol(enclosedExecutableElement)
    } yield symbol
    overriddenSymbols.toList.asJava
  }

  private def semanticdbSymbol(element: Element): String = {

    @tailrec
    def descriptors(
        acc: List[Descriptor],
        element: Element
    ): List[Descriptor] = {
      if (element == null || element.getSimpleName.toString == "") {
        if (acc.isEmpty) Empty :: Nil
        else acc
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
      case d @ (Package(_) :: _) => d
      case d => Package("_empty_") :: d
    }).mkString("")
  }

  private def disambiguator(executableElement: ExecutableElement): String = {
    val methods =
      executableElement.getEnclosingElement.getEnclosedElements.asScala
        .collect {
          case e: ExecutableElement
              if e.getSimpleName == executableElement.getSimpleName =>
            e
        }

    val index = methods.zipWithIndex.collectFirst {
      case (e, i) if e.equals(executableElement) => i
    }

    index match {
      case Some(i) => if (i == 0) "()" else s"(+$i)"
      case None => "()"
    }
  }

  object Symbols {
    val None: String = ""
    val RootPackage: String = "_root_/"
  }
}

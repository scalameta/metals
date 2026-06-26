package scala.meta.internal.jpc

import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

import scala.jdk.CollectionConverters._

import com.sun.source.tree.ClassTree
import com.sun.source.tree.MethodTree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

private class MissingOverrideDiagnosticProvider(
    compiler: JavaMetalsCompiler,
    compile: JavaSourceCompile,
    text: String
) {

  private val task = compile.task
  private val trees = Trees.instance(task)
  private val sourcePositions = trees.getSourcePositions()
  private val elements = task.getElements()
  private val types = task.getTypes()
  private val lineMap = compile.cu.getLineMap()

  def diagnostics(): List[l.Diagnostic] = {
    val diagnostics = List.newBuilder[l.Diagnostic]
    val scanner = new TreePathScanner[Unit, Option[TypeElement]] {
      override def visitClass(
          node: ClassTree,
          owner: Option[TypeElement]
      ): Unit =
        super.visitClass(node, typeElement(getCurrentPath()).orElse(owner))

      override def visitMethod(
          node: MethodTree,
          owner: Option[TypeElement]
      ): Unit = {
        for {
          classElement <- owner
          method <- executableElement(getCurrentPath())
          if method.getKind() == ElementKind.METHOD
          if !hasOverrideAnnotation(node)
          if overridesSuperMethod(method, classElement)
          range <- methodNameRange(node)
        } {
          val diagnostic = new l.Diagnostic(
            range,
            s"missing @Override annotation on '${node.getName()}'",
            l.DiagnosticSeverity.Warning,
            "javac"
          )
          diagnostic.setCode(
            MissingOverrideDiagnosticProvider.MissingOverrideCode
          )
          diagnostics += diagnostic
        }
        super.visitMethod(node, owner)
      }
    }

    scanner.scan(compile.cu, None)
    diagnostics.result()
  }

  private def typeElement(path: TreePath): Option[TypeElement] =
    trees.getElement(path) match {
      case element: TypeElement => Some(element)
      case _ => None
    }

  private def executableElement(path: TreePath): Option[ExecutableElement] =
    trees.getElement(path) match {
      case executable: ExecutableElement => Some(executable)
      case _ => None
    }

  private def hasOverrideAnnotation(method: MethodTree): Boolean =
    method.getModifiers().getAnnotations().asScala.exists { annotation =>
      val name = annotation.getAnnotationType().toString()
      name == "Override" || name == "java.lang.Override"
    }

  private def overridesSuperMethod(
      method: ExecutableElement,
      owner: TypeElement
  ): Boolean =
    superTypes(owner).exists { superType =>
      superType.getEnclosedElements().asScala.exists {
        case candidate: ExecutableElement
            if candidate.getKind() == ElementKind.METHOD =>
          elements.overrides(method, candidate, owner)
        case _ => false
      }
    }

  private def superTypes(owner: TypeElement): List[TypeElement] = {
    val seen = scala.collection.mutable.LinkedHashSet.empty[TypeElement]
    def loop(element: TypeElement): Unit =
      for {
        superType <- types.directSupertypes(element.asType()).asScala
        superElement <- types.asElement(superType) match {
          case element: TypeElement => Some(element)
          case _ => None
        }
        if seen.add(superElement)
      } loop(superElement)
    loop(owner)
    seen.toList
  }

  private def methodNameRange(method: MethodTree): Option[l.Range] = {
    val start = sourcePositions.getStartPosition(compile.cu, method)
    val end = sourcePositions.getEndPosition(compile.cu, method)
    if (start < 0 || end < 0) None
    else {
      val (nameStart, nameEnd) = compiler.findIndentifierStartAndEnd(
        text,
        method.getName().toString(),
        start.toInt,
        end.toInt,
        method,
        compile.cu,
        sourcePositions
      )
      Some(Positions.toLspRange(lineMap, nameStart, nameEnd, text))
    }
  }
}

object MissingOverrideDiagnosticProvider {
  val MissingOverrideCode = "missing-override-annotation"
}

package scala.meta.internal.pc

import java.util
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.jdk.CollectionConverters.SeqHasAsJava

import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.pc.ContentType
import scala.meta.pc.OffsetParams

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.NewClassTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import org.eclipse.lsp4j.ParameterInformation
import org.eclipse.lsp4j.SignatureHelp
import org.eclipse.lsp4j.SignatureInformation

class JavaSignatureHelpProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams
) {

  def signatureHelp(): SignatureHelp = {
    val task: JavacTask =
      compiler.compilationTask(params.text(), params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)

    // Always scan the entire compilation unit to find method calls containing the cursor
    val methodCall = findMethodCallContainingOffset(scanner.root, task, scanner)

    methodCall match {
      case Some((treePath, activeParam)) =>
        createSignatureHelp(treePath, activeParam, task)
      case None =>
        new SignatureHelp()
    }
  }

  private def findMethodCallContainingOffset(
      root: CompilationUnitTree,
      task: JavacTask,
      scanner: JavaTreeScanner
  ): Option[(TreePath, Int)] = {
    val trees = Trees.instance(task)
    val sourcePos = trees.getSourcePositions
    val offset = params.offset()

    // Find the smallest/innermost method call containing the cursor
    var result: Option[(TreePath, Int, Long)] =
      None // (path, activeParam, size)

    def checkMethod(
        node: ExpressionTree,
        arguments: util.List[_ <: ExpressionTree],
        startNode: ExpressionTree
    ) = {

      val start = sourcePos.getEndPosition(root, startNode)
      val end = sourcePos.getEndPosition(root, node)

      if (start <= offset && offset <= end) {
        val size = end - start
        val path = trees.getPath(root, node)
        val activeParam =
          if (arguments == null || arguments.isEmpty()) 0
          else findActiveParameter(arguments.asScala.toList, task, scanner)

        // Keep the smallest (innermost) match
        result match {
          case Some((_, _, currentSize)) if size < currentSize =>
            result = Some((path, activeParam, size))
          case None =>
            result = Some((path, activeParam, size))
          case _ => // Keep current result
        }
      }
    }

    val methodScanner = new TreeScanner[Void, Void] {
      override def visitMethodInvocation(
          node: MethodInvocationTree,
          p: Void
      ): Void = {
        node.getMethodSelect()
        checkMethod(node, node.getArguments(), node.getMethodSelect())
        super.visitMethodInvocation(node, p)
      }

      override def visitNewClass(node: NewClassTree, p: Void): Void = {
        checkMethod(node, node.getArguments(), node.getIdentifier())
        super.visitNewClass(node, p)
      }
    }

    methodScanner.scan(root, null)
    result.map { case (path, activeParam, _) => (path, activeParam) }
  }

  private def findActiveParameter(
      arguments: List[ExpressionTree],
      task: JavacTask,
      scanner: JavaTreeScanner
  ): Int = {

    val trees = Trees.instance(task)
    val sourcePos = trees.getSourcePositions
    val offset = params.offset()

    // Find which argument the cursor is in
    val indexOpt = arguments.zipWithIndex
      .find { case (arg, _) =>
        val start = sourcePos.getStartPosition(scanner.root, arg)
        val end = sourcePos.getEndPosition(scanner.root, arg)
        start <= offset && offset <= end
      }
      .map(_._2)

    indexOpt.getOrElse {
      // If cursor is between arguments or after last argument
      if (arguments.nonEmpty) {
        val lastArgEnd = sourcePos.getEndPosition(scanner.root, arguments.last)
        if (offset > lastArgEnd) {
          // Cursor is after the last argument - we're potentially adding a new one
          arguments.length - 1
        } else {
          0
        }
      } else {
        0
      }
    }
  }

  private def createSignatureHelp(
      invocationPath: TreePath,
      activeParam: Int,
      task: JavacTask
  ): SignatureHelp = {
    val trees = Trees.instance(task)
    val element = trees.getElement(invocationPath)
    if (element == null) {
      return new SignatureHelp()
    }

    val signatures = invocationPath.getLeaf() match {
      case _: MethodInvocationTree =>
        element match {
          case exec: ExecutableElement =>
            List(createExecutableSignature(exec, task))
          // myMethodWithParameters(@@) <- fallback when nothing is yet written
          case cls: TypeElement =>
            val alternatives = cls
              .getEnclosingElement()
              .getEnclosedElements()
              .asScala
              .toList
              .filter(_.getSimpleName() == cls.getSimpleName())
            alternatives.collect { case exec: ExecutableElement =>
              createExecutableSignature(exec, task)
            }
          case _ =>
            Nil
        }
      case _: NewClassTree =>
        element match {
          case typeElement: TypeElement =>
            collectConstructorSignatures(typeElement)
          case exec: ExecutableElement
              if exec.getKind == ElementKind.CONSTRUCTOR =>
            // Sometimes constructors are resolved as ExecutableElement directly
            val className = exec.getEnclosingElement.getSimpleName.toString
            List(createConstructorSignature(exec, className))
          case _ =>
            Nil
        }
      case _ =>
        Nil
    }

    if (signatures.isEmpty) {
      new SignatureHelp()
    } else {
      new SignatureHelp(
        signatures.asJava,
        0, // active signature
        Math.min(activeParam, signatures.head.getParameters.size() - 1)
      )
    }
  }

  private def createExecutableSignature(
      exec: ExecutableElement,
      task: JavacTask
  ): SignatureInformation = {

    val methodName = exec.getSimpleName.toString
    val returnType = simplifyType(typeToString(exec.getReturnType))
    val params = exec.getParameters.asScala.toList
    val types = task.getTypes()
    val elements = task.getElements()

    val docs =
      compiler.documentation(exec, types, elements, ContentType.MARKDOWN)
    val paramDocs = docs.toList
      .flatMap(_.parameters().asScala.toList)
      .map(_.displayName())

    val paramLabels = params.zipWithIndex.map { case (param, index) =>
      val paramType = simplifyType(typeToString(param.asType()))
      val paramDoc =
        paramDocs.lift(index).getOrElse(param.getSimpleName().toString())
      new ParameterInformation(s"$paramType $paramDoc")
    }

    val paramString = paramLabels.map(_.getLabel.getLeft).mkString(", ")
    val signature = s"$returnType $methodName($paramString)"
    val textDocstring = docs.toList.headOption.map(_.docstring()).getOrElse("")
    val sigInfo = new SignatureInformation(signature)
    sigInfo.setDocumentation(
      textDocstring.toMarkupContent()
    )
    sigInfo.setParameters(paramLabels.asJava)
    sigInfo
  }

  private def collectConstructorSignatures(
      typeElement: TypeElement
  ): List[SignatureInformation] = {
    def fromElement(element: Element): List[ExecutableElement] =
      element.getEnclosedElements.asScala
        .filter(_.getKind == ElementKind.CONSTRUCTOR)
        .collect { case exec: ExecutableElement => exec }
        .toList

    val constructors = fromElement(typeElement)

    val className = typeElement.getSimpleName.toString
    val constructorsFallback =
      if (constructors.isEmpty) fromElement(typeElement.getEnclosingElement())
      else constructors

    constructorsFallback.map { ctor =>
      createConstructorSignature(ctor, className)
    }

  }

  private def createConstructorSignature(
      ctor: ExecutableElement,
      className: String
  ): SignatureInformation = {
    val params = ctor.getParameters.asScala.toList

    val paramLabels = params.map { param =>
      val paramType = simplifyType(typeToString(param.asType()))
      val paramName = param.getSimpleName.toString
      new ParameterInformation(s"$paramType $paramName")
    }

    val paramString = paramLabels.map(_.getLabel.getLeft).mkString(", ")
    val signature = s"$className($paramString)"

    val sigInfo = new SignatureInformation(signature)
    sigInfo.setParameters(paramLabels.asJava)
    sigInfo
  }

  private def typeToString(
      typeMirror: TypeMirror
  ): String = {
    typeMirror.accept(new JavaTypeVisitor(), null)
  }

  private def simplifyType(typeName: String): String = {
    // Remove package names from common types
    typeName
      .replaceAll("java\\.lang\\.", "")
      .replaceAll("java\\.util\\.", "")
      .replaceAll("java\\.io\\.", "")
  }

}

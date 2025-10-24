package scala.meta.internal.jpc

import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement

import scala.jdk.CollectionConverters._

import scala.meta.pc.ContentType
import scala.meta.pc.OffsetParams

import com.sun.source.tree.AnnotationTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

class JavaSignatureHelpProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams,
    contentType: ContentType
) {
  def signatureHelp(): l.SignatureHelp = {
    val compile = compiler
      .compilationTask(params)
      .withAnalyzePhase()
    val cu = compile.cu
    val scanner = new SignatureHelpScanner(compile.task, cu, params.offset())
    scanner.scan(cu, ())
    val result = for {
      enclosing <- scanner.lastInvocation
    } yield {
      val argumentIndex = scanner.lastArgumentIndex.getOrElse(0)
      val element = Trees.instance(compile.task).getElement(enclosing)
      val disambiguated = disambiguate(compile, element)
      val signatures =
        disambiguated.signatures.collect {
          case s: ExecutableElement =>
            executableElementInformation(compile.task, s)
          case tpe: TypeElement
              if tpe.getKind() == ElementKind.ANNOTATION_TYPE =>
            annotationInformation(tpe)
        }
      new l.SignatureHelp(
        signatures.asJava,
        disambiguated.activeSignatureIndex,
        argumentIndex
      )
    }
    result.getOrElse(new l.SignatureHelp())
  }

  private def annotationInformation(
      annotation: TypeElement
  ): l.SignatureInformation = {
    val parameterInfos = annotation
      .getEnclosedElements()
      .asScala
      .collect { case e: ExecutableElement =>
        val default = Option(e.getDefaultValue()) match {
          case Some(value) => s" default ${value}"
          case None => ""
        }
        new l.ParameterInformation(
          s"${e.getReturnType} ${e.getSimpleName()}()$default"
        )
      }
      .toList
    val label =
      s"@${annotation.getSimpleName()}(${renderParameterInfos(parameterInfos)})"
    new l.SignatureInformation(
      label,
      new l.MarkupContent(),
      parameterInfos.asJava
    )
  }
  private def executableElementInformation(
      task: JavacTask,
      element: ExecutableElement
  ): l.SignatureInformation = {
    val documentation = new l.MarkupContent()
    if (compiler.metalsConfig.isHoverDocumentationEnabled) {
      documentation.setKind(
        if (contentType == ContentType.MARKDOWN) l.MarkupKind.MARKDOWN
        else l.MarkupKind.PLAINTEXT
      )
      val doc = compiler.documentation(task, element)
      documentation.setValue(doc)
    }
    val parameterInfos: List[l.ParameterInformation] =
      element
        .getParameters()
        .asScala
        .map(param => {
          val tpe = param.asType()
          new l.ParameterInformation(s"${tpe} $param")
        })
        .toList
    val simpleName =
      if (element.getKind() == ElementKind.CONSTRUCTOR)
        element.getEnclosingElement().getSimpleName()
      else element.getSimpleName()
    val label = s"${simpleName}(${renderParameterInfos(parameterInfos)})"
    new l.SignatureInformation(
      label,
      documentation,
      parameterInfos.asJava
    )
  }

  private def renderParameterInfos(
      infos: collection.Seq[l.ParameterInformation]
  ): String = {
    infos.map(_.getLabel().getLeft()).mkString(", ")
  }

  private case class SignatureCandidates(
      signatures: List[Element],
      activeSignatureIndex: Int
  )
  private def disambiguate(
      compile: JavaSourceCompile,
      element: Element
  ): SignatureCandidates = {
    val elements = compile.task.getElements()
    element match {
      case tpe: TypeElement if tpe.getKind() == ElementKind.ANNOTATION_TYPE =>
        // Annotation interfaces don't have overloads. We treat the whole
        // interface as a single signature.
        return SignatureCandidates(List(tpe), 0)
      case _ =>
    }

    val parent = element.getEnclosingElement() match {
      case e: TypeElement => e
      case _ =>
        return SignatureCandidates(Nil, 0)
    }

    val candidates = elements
      .getAllMembers(parent)
      .asScala
      .filter(member =>
        member.getEnclosingElement() == parent &&
          member.getSimpleName() == element.getSimpleName()
      )
    val executableCandidates = candidates.collect { case e: ExecutableElement =>
      e
    }.toList
    val activeSignatureIndex =
      Math.max(0, executableCandidates.indexOf(element))
    SignatureCandidates(executableCandidates, activeSignatureIndex)
  }

  private class SignatureHelpScanner(
      task: JavacTask,
      cu: CompilationUnitTree,
      targetOffset: Int
  ) extends TreePathScanner[TreePath, Unit] {
    val trees: Trees = Trees.instance(task)
    val sourcePositions = trees.getSourcePositions
    var lastInvocation: Option[TreePath] = None
    var lastArgumentIndex: Option[Int] = None
    override def scan(tree: Tree, p: Unit): TreePath = {
      val start = sourcePositions.getStartPosition(cu, tree)
      val end = sourcePositions.getEndPosition(cu, tree)
      if (targetOffset < start || targetOffset > end) {
        return null
      }
      try {
        super.scan(tree, ())
      } catch {
        case _: AssertionError =>
          // ignore
          null
      }
    }

    override def visitNewClass(node: NewClassTree, p: Unit): TreePath = {
      val selectEnd = sourcePositions.getEndPosition(cu, node.getIdentifier())
      if (selectEnd > targetOffset) {
        return super.visitNewClass(node, p)
      }
      lastInvocation = Some(getCurrentPath())
      var r = scan(node.getEnclosingExpression(), p);
      r = scanAndReduce(node.getIdentifier(), p, r);
      r = scanAndReduce(node.getTypeArguments(), p, r);
      r = scanAndReduceArguments(node.getArguments(), p, r);
      r = scanAndReduce(node.getClassBody(), p, r);
      r
    }
    override def visitAnnotation(node: AnnotationTree, p: Unit): TreePath = {
      val selectEnd =
        sourcePositions.getEndPosition(cu, node.getAnnotationType())
      if (selectEnd > targetOffset) {
        return super.visitAnnotation(node, p)
      }
      lastInvocation = Some(getCurrentPath())
      var r = super.scan(node.getAnnotationType(), p);
      r = scanAndReduceArguments(node.getArguments(), p, r);
      r
    }
    override def visitMethodInvocation(
        node: MethodInvocationTree,
        p: Unit
    ): TreePath = {
      val selectEnd = sourcePositions.getEndPosition(cu, node.getMethodSelect())
      if (selectEnd > targetOffset) {
        return super.visitMethodInvocation(node, p)
      }
      lastInvocation = Some(getCurrentPath())
      var r = super.scan(node.getTypeArguments(), p);
      r = this.scanAndReduce(node.getMethodSelect(), p, r);
      r = this.scanAndReduceArguments(node.getArguments(), p, r);
      r;
    }
    private def scanAndReduce(node: Tree, p: Unit, r: TreePath): TreePath = {
      return super.reduce(super.scan(node, p), r);
    }
    private def scanAndReduce(
        nodes: java.lang.Iterable[_ <: Tree],
        p: Unit,
        r: TreePath
    ): TreePath = {
      super.reduce(super.scan(nodes, p), r)
    }
    private def scanAndReduceArguments(
        nodes: java.lang.Iterable[_ <: Tree],
        p: Unit,
        r: TreePath
    ): TreePath = {
      var r: TreePath = null;
      if (nodes != null) {
        var first = true;
        for ((node, index) <- nodes.iterator().asScala.zipWithIndex) {
          val start = sourcePositions.getStartPosition(cu, node)
          val end = sourcePositions.getEndPosition(cu, node)
          if (start <= targetOffset && end >= targetOffset) {
            lastArgumentIndex = Some(index)
          }
          r =
            if (first) scan(node, p)
            else scanAndReduce(node, p, r)
          first = false;
        }
      }
      return r;
      return super.reduce(super.scan(nodes, p), r);
    }
  }

}

package scala.meta.internal.jpc

import com.sun.source.tree.*
import com.sun.source.util.{TreePath, TreePathScanner, Trees}
import org.eclipse.lsp4j.{InlayHint, InlayHintKind, Range}

import java.util.concurrent.CancellationException
import javax.lang.model.`type`.*
import javax.lang.model.element.{ExecutableElement, NestingKind, TypeElement}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.meta.internal.pc.{InlayHintOrigin, InlayHints, LabelPart}
import scala.meta.pc.InlayHintsParams
import scala.util.control.NonFatal

/**
 * Computes inlay hints for Java sources:
 *
 *  - inferred types of local variables declared with `var` (enabled by the
 *    `inferredTypes` option), e.g. `var list/*: List<String>*/ = ...`,
 *  - parameter names of arguments passed to methods and constructors
 *    (enabled by the `namedParameters` option), e.g. `max(/*a: */1, /*b: */2)`.
 *
 * Hints that do not add information are suppressed: `var` initialized with
 * `new`, a cast or a literal, arguments that already spell out the parameter
 * name, and parameters whose names are unknown (`arg0`) or a single letter.
 */
final class JavaInlayHintsProvider(
    compiler: JavaMetalsCompiler,
    params: InlayHintsParams
) {

  def inlayHints(): List[InlayHint] = {
    if (!params.inferredTypes() && !params.namedParameters()) Nil
    else {
      params.checkCanceled()
      val compile = compiler.compilationTask(params).withAnalyzePhase()
      params.checkCanceled()
      val scanner =
        new InlayHintsScanner(Trees.instance(compile.task), compile.cu)
      scanner.scan(compile.cu, ())
      scanner.result()
    }
  }

  private final class InlayHintsScanner(
      trees: Trees,
      cu: CompilationUnitTree
  ) extends TreePathScanner[Unit, Unit] {
    private val positions = trees.getSourcePositions
    private val text = cu.getSourceFile.getCharContent(true).toString
    // Same builder as in the Scala providers, it deduplicates inferred types.
    private var hints = InlayHints.empty(params.uri())

    def result(): List[InlayHint] =
      hints
        .result()
        .sortBy(h => (h.getPosition.getLine, h.getPosition.getCharacter))

    override def scan(tree: Tree, p: Unit): Unit =
      if (tree != null && overlapsRequestedRange(tree)) super.scan(tree, p)

    override def visitVariable(node: VariableTree, p: Unit): Unit = {
      if (params.inferredTypes()) guarded(inferredTypeHint(node))
      super.visitVariable(node, p)
    }

    override def visitMethodInvocation(
        node: MethodInvocationTree,
        p: Unit
    ): Unit = {
      if (params.namedParameters()) {
        val method = new TreePath(getCurrentPath, node.getMethodSelect)
        guarded(
          parameterHints(trees.getElement(method), node.getArguments.asScala)
        )
      }
      super.visitMethodInvocation(node, p)
    }

    override def visitNewClass(node: NewClassTree, p: Unit): Unit = {
      if (params.namedParameters()) {
        guarded(
          parameterHints(
            trees.getElement(getCurrentPath),
            node.getArguments.asScala
          )
        )
      }
      super.visitNewClass(node, p)
    }

    // A malformed node must not fail the whole request, skip its hints.
    private def guarded(f: => Unit): Unit =
      try f
      catch {
        case e: CancellationException => throw e
        case NonFatal(_) =>
      }

    private def overlapsRequestedRange(tree: Tree): Boolean = {
      val start = positions.getStartPosition(cu, tree)
      val end = positions.getEndPosition(cu, tree)
      // Trees without positions (synthetic) are scanned, their children may
      // still have positions.
      start < 0 || end < 0 ||
      (end >= params.offset() && start <= params.endOffset())
    }

    private def inRequestedRange(offset: Int): Boolean =
      offset >= params.offset() && offset <= params.endOffset()

    private def addHint(
        offset: Int,
        labelParts: List[LabelPart],
        kind: InlayHintKind,
        origin: InlayHintOrigin
    ): Unit = {
      val position = Positions.toLspPosition(cu.getLineMap, offset, text)
      hints = hints.add(new Range(position, position), labelParts, kind, origin)
    }

    private def inferredTypeHint(node: VariableTree): Unit = {
      val start = positions.getStartPosition(cu, node).toInt
      val end = positions.getEndPosition(cu, node).toInt
      val name = node.getName.toString
      for {
        nameOffset <- Positions.findNameOffset(text, start, end, name)
        nameEnd = nameOffset + name.length()
        if inRequestedRange(nameEnd)
        if isDeclaredWithVar(start, nameOffset)
        if !hasObviousType(node.getInitializer)
        element <- Option(trees.getElement(getCurrentPath))
        printer = new HintTypePrinter()
        label = printer.visit(element.asType())
        if label.nonEmpty && !printer.hasErrors
      } {
        params.checkCanceled()
        addHint(
          nameEnd,
          InlayHints.makeLabelParts(printer.labelParts, s": $label"),
          InlayHintKind.Type,
          InlayHintOrigin.InferredType
        )
      }
    }

    /**
     * Whether the last token before the variable name is the `var` keyword.
     * Comparing source text avoids relying on javac internals, which differ
     * between JDK versions.
     */
    private def isDeclaredWithVar(start: Int, nameOffset: Int): Boolean = {
      start >= 0 && start <= nameOffset && {
        val beforeName = text.substring(start, nameOffset).trim()
        beforeName.endsWith("var") && {
          val varStart = beforeName.length() - 3
          varStart == 0 ||
          !Character.isJavaIdentifierPart(beforeName.charAt(varStart - 1))
        }
      }
    }

    @tailrec
    private def hasObviousType(initializer: ExpressionTree): Boolean =
      initializer match {
        case parens: ParenthesizedTree => hasObviousType(parens.getExpression)
        case _: NewClassTree | _: NewArrayTree | _: TypeCastTree |
            _: LiteralTree =>
          true
        case _ => false
      }

    private def parameterHints(
        element: javax.lang.model.element.Element,
        arguments: Iterable[ExpressionTree]
    ): Unit = element match {
      case method: ExecutableElement if arguments.nonEmpty =>
        val parameters = method.getParameters.asScala.toIndexedSeq
        val lastIndex = parameters.length - 1
        for {
          case (argument, index) <- arguments.zipWithIndex
          // Extra arguments passed to a varargs parameter get no hints of
          // their own, only the first one does.
          if index <= lastIndex
          name = parameters(index).getSimpleName.toString
          // The name is known only to the compiler (`arg0`, `x0`) or too
          // short to tell anything.
          if isInformativeName(name)
          // `setName(name)`: the method name already says it all.
          if parameters.length != 1 || !methodNameEndsWith(method, name)
          start = positions.getStartPosition(cu, argument).toInt
          // Synthetic arguments have no position and the hint has to be in
          // the requested range.
          if start >= 0 && inRequestedRange(start)
          // Walks the argument tree, so it goes last: `foo(value)` for a
          // parameter named `value` doesn't need a hint either.
          if !argumentMatchesName(argument, name)
        } {
          params.checkCanceled()
          // `...values: 1, 2` marks where the varargs start, unless the
          // caller passes an array directly.
          val prefix =
            if (
              method.isVarArgs && index == lastIndex &&
              !isArrayArgument(argument)
            ) "..."
            else ""
          addHint(
            start,
            List(LabelPart(s"$prefix$name: ")),
            InlayHintKind.Parameter,
            InlayHintOrigin.NamedParameters
          )
        }
      case _ =>
    }

    private def isArrayArgument(argument: ExpressionTree): Boolean =
      Option(trees.getTypeMirror(new TreePath(getCurrentPath, argument)))
        .exists(_.getKind == TypeKind.ARRAY)

    private def isInformativeName(name: String): Boolean =
      name.length > 1 && !SyntheticParameterName.matches(name)

    @tailrec
    private def argumentMatchesName(
        argument: ExpressionTree,
        name: String
    ): Boolean = {
      def matches(argumentName: String): Boolean = {
        val lowerArg = argumentName.toLowerCase()
        val lowerName = name.toLowerCase()
        lowerArg == lowerName || lowerArg.endsWith(lowerName) ||
        lowerArg == s"get$lowerName" || lowerArg == s"is$lowerName"
      }
      argument match {
        case parens: ParenthesizedTree =>
          argumentMatchesName(parens.getExpression, name)
        case ident: IdentifierTree => matches(ident.getName.toString)
        case select: MemberSelectTree =>
          matches(select.getIdentifier.toString)
        case invocation: MethodInvocationTree
            if invocation.getArguments.isEmpty =>
          argumentMatchesName(invocation.getMethodSelect, name)
        case _ => false
      }
    }

    private def methodNameEndsWith(
        method: ExecutableElement,
        name: String
    ): Boolean =
      method.getSimpleName.toString
        .toLowerCase()
        .endsWith(name.toLowerCase())
  }

  /**
   * Prints types with simple names, as they would usually be written in
   * source, and collects the label parts needed to resolve each type name to
   * its definition.
   */
  private final class HintTypePrinter extends JavaTypeVisitor {
    private val parts = mutable.ListBuffer.empty[LabelPart]
    var hasErrors = false

    def labelParts: List[LabelPart] = parts.toList

    override def visitDeclared(t: DeclaredType, p: Void): String =
      t.asElement() match {
        case element: TypeElement
            if element.getNestingKind == NestingKind.ANONYMOUS =>
          // Anonymous classes can't be named, show their supertype instead.
          val supertype =
            element.getInterfaces.asScala.headOption
              .getOrElse(element.getSuperclass)
          visit(supertype)
        case element: TypeElement =>
          val name = typeName(element)
          parts += LabelPart(name, symbol(element))
          val arguments = t.getTypeArguments.asScala
          if (arguments.isEmpty) name
          else arguments.map(visit).mkString(s"$name<", ", ", ">")
        case _ => super.visitDeclared(t, p)
      }

    override def visitTypeVariable(t: TypeVariable, p: Void): String =
      t match {
        // Captured wildcards (`capture#1 of ? extends T`) can't be written in
        // source, show the wildcard they were captured from instead.
        case captured if captured.toString.startsWith("capture#") =>
          visit(captured.getUpperBound)
        case _ => t.asElement().getSimpleName.toString
      }

    override def visitError(t: ErrorType, p: Void): String = {
      hasErrors = true
      ""
    }

    override def visit(t: TypeMirror, p: Void): String =
      if (t == null || t.getKind == TypeKind.NONE) ""
      else super.visit(t, p)

    private def typeName(element: TypeElement): String =
      element.getEnclosingElement match {
        case outer: TypeElement
            if element.getNestingKind == NestingKind.MEMBER =>
          s"${typeName(outer)}.${element.getSimpleName}"
        case _ => element.getSimpleName.toString
      }

    private def symbol(element: TypeElement): String =
      element.getNestingKind match {
        case NestingKind.TOP_LEVEL | NestingKind.MEMBER =>
          SemanticdbSymbol.fromElement(element)
        case _ => ""
      }
  }

  private val SyntheticParameterName = "(arg|x)\\d+".r
}

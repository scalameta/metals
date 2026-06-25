package scala.meta.internal.jpc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.ExecutableType
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement

import scala.jdk.CollectionConverters._

import scala.meta.pc.OffsetParams

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

/**
 * Generates implementations for the abstract members that the class at the
 * cursor position inherits but does not yet implement.
 *
 * The cursor is expected to be on the class declaration that triggers the
 * `compiler.err.does.not.override.abstract` diagnostic.
 */
class JavaImplementAbstractMembersProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams
) {

  private val placeholderBody =
    """throw new UnsupportedOperationException("Not yet implemented");"""

  def implementAbstractMembers(): List[l.TextEdit] = {
    val compile = compiler.compilationTask(params).withAnalyzePhase()
    val task = compile.task
    val cu = compile.cu
    val trees = Trees.instance(task)
    val text = params.text()

    enclosingClass(trees, cu).flatMap { classPath =>
      trees.getElement(classPath) match {
        case typeElement: TypeElement
            if !typeElement.getModifiers.contains(Modifier.ABSTRACT) &&
              typeElement.getKind() != ElementKind.INTERFACE &&
              typeElement.getKind() != ElementKind.ANNOTATION_TYPE =>
          val abstractMethods =
            unimplementedAbstractMethods(task, typeElement)
          if (abstractMethods.isEmpty) None
          else
            Some(
              edit(
                trees,
                cu,
                classPath.getLeaf().asInstanceOf[ClassTree],
                text,
                abstractMethods
              )
            )
        case _ => None
      }
    }.toList
  }

  private def enclosingClass(
      trees: Trees,
      cu: CompilationUnitTree
  ): Option[TreePath] = {
    val finder = new EnclosingClassFinder(trees, cu, params.offset())
    finder.scan(cu, ())
    finder.result
  }

  /**
   * Returns the abstract methods inherited by `typeElement` that are not
   * implemented, paired with their type as seen from `typeElement` (so that
   * generic type parameters are substituted). Sorted for deterministic output.
   */
  private def unimplementedAbstractMethods(
      task: JavacTask,
      typeElement: TypeElement
  ): List[(ExecutableElement, ExecutableType)] = {
    val elements = task.getElements()
    val types = task.getTypes()
    val declaredType = typeElement.asType().asInstanceOf[DeclaredType]
    val methods =
      for {
        member <- elements.getAllMembers(typeElement).asScala.toList
        if member.getKind() == ElementKind.METHOD &&
          member.getModifiers().contains(Modifier.ABSTRACT)
        method = member.asInstanceOf[ExecutableElement]
        execType = types
          .asMemberOf(declaredType, method)
          .asInstanceOf[ExecutableType]
      } yield (method, execType)
    methods.sortBy { case (method, execType) => sortKey(method, execType) }
  }

  private def sortKey(
      method: ExecutableElement,
      execType: ExecutableType
  ): String =
    method.getSimpleName().toString() +
      execType
        .getParameterTypes()
        .asScala
        .map(JavaLabels.typeLabel)
        .mkString("(", ",", ")")

  private def edit(
      trees: Trees,
      cu: CompilationUnitTree,
      classTree: ClassTree,
      text: String,
      methods: List[(ExecutableElement, ExecutableType)]
  ): l.TextEdit = {
    val lineMap = cu.getLineMap()
    val sourcePositions = trees.getSourcePositions()
    val classStart = sourcePositions.getStartPosition(cu, classTree).toInt
    val classEnd = sourcePositions.getEndPosition(cu, classTree).toInt
    val closeBrace = classEnd - 1

    val classIndent = lineIndent(text, classStart)
    val indentUnit = detectIndentUnit(text)
    val memberIndent = classIndent + indentUnit
    val bodyIndent = memberIndent + indentUnit

    // Find the insertion point: right after the last non-whitespace character
    // before the closing brace of the class body.
    var lastNonWs = closeBrace - 1
    while (lastNonWs > classStart && text.charAt(lastNonWs).isWhitespace)
      lastNonWs -= 1
    val isEmptyBody = text.charAt(lastNonWs) == '{'
    val insertStart = lastNonWs + 1

    val blocks =
      methods
        .map { case (method, execType) =>
          methodStub(method, execType, memberIndent, bodyIndent)
        }
        .mkString("\n\n")

    val prefix = if (isEmptyBody) "\n" else "\n\n"
    val suffix = s"\n$classIndent"

    new l.TextEdit(
      Positions.toLspRange(lineMap, insertStart, closeBrace, text),
      s"$prefix$blocks$suffix"
    )
  }

  private def methodStub(
      method: ExecutableElement,
      execType: ExecutableType,
      memberIndent: String,
      bodyIndent: String
  ): String = {
    val signature = methodSignature(method, execType)
    s"""$memberIndent@Override
       |$memberIndent$signature {
       |$bodyIndent$placeholderBody
       |$memberIndent}""".stripMargin
  }

  private def methodSignature(
      method: ExecutableElement,
      execType: ExecutableType
  ): String = {
    val modifier =
      if (method.getModifiers().contains(Modifier.PUBLIC)) "public "
      else if (method.getModifiers().contains(Modifier.PROTECTED)) "protected "
      else ""
    val typeParams = typeParameters(method)
    val returnType = JavaLabels.typeLabel(execType.getReturnType())
    val name = method.getSimpleName().toString()
    val params = parameters(method, execType)
    s"$modifier$typeParams$returnType $name($params)"
  }

  private def typeParameters(method: ExecutableElement): String = {
    val typeParams = method.getTypeParameters().asScala.toList
    if (typeParams.isEmpty) ""
    else {
      val rendered = typeParams.map(renderTypeParameter).mkString(", ")
      s"<$rendered> "
    }
  }

  private def renderTypeParameter(tp: TypeParameterElement): String = {
    val bounds = tp
      .getBounds()
      .asScala
      .toList
      .filterNot(b => JavaLabels.typeLabel(b) == "java.lang.Object")
    if (bounds.isEmpty) tp.getSimpleName().toString()
    else
      s"${tp.getSimpleName()} extends ${bounds.map(JavaLabels.typeLabel).mkString(" & ")}"
  }

  private def parameters(
      method: ExecutableElement,
      execType: ExecutableType
  ): String = {
    val paramTypes = execType.getParameterTypes().asScala.toList
    val paramNames = method.getParameters().asScala.toList
    val isVarArgs = method.isVarArgs()
    paramTypes
      .zip(paramNames)
      .zipWithIndex
      .map { case ((paramType, param), index) =>
        val name = param.getSimpleName().toString()
        val isLast = index == paramTypes.length - 1
        if (isVarArgs && isLast) {
          paramType match {
            case array: ArrayType =>
              s"${JavaLabels.typeLabel(array.getComponentType())}... $name"
            case _ => s"${JavaLabels.typeLabel(paramType)} $name"
          }
        } else s"${JavaLabels.typeLabel(paramType)} $name"
      }
      .mkString(", ")
  }

  private def lineIndent(text: String, offset: Int): String = {
    val clamped = offset.min(text.length()).max(0)
    val lineStart = text.lastIndexOf('\n', clamped - 1) + 1
    text.substring(lineStart, clamped).takeWhile(_.isWhitespace)
  }

  private def detectIndentUnit(text: String): String =
    text.linesIterator
      .map(_.takeWhile(c => c == ' ' || c == '\t'))
      .find(_.nonEmpty) match {
      case Some(ws) if ws.startsWith("\t") => "\t"
      case _ => "  "
    }

  private class EnclosingClassFinder(
      trees: Trees,
      cu: CompilationUnitTree,
      offset: Int
  ) extends TreePathScanner[Unit, Unit] {
    private val sourcePositions = trees.getSourcePositions()
    private var found: Option[TreePath] = None

    def result: Option[TreePath] = found

    override def visitClass(node: ClassTree, p: Unit): Unit = {
      val start = sourcePositions.getStartPosition(cu, node)
      val end = sourcePositions.getEndPosition(cu, node)
      if (start >= 0 && end >= 0 && start <= offset && offset <= end) {
        // The innermost enclosing class is visited last in pre-order traversal.
        found = Some(getCurrentPath())
      }
      super.visitClass(node, p)
    }
  }
}

package scala.meta.internal.jpc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.ExecutableType
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement

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
 *
 * Referenced types are rendered with simple names where it is safe, and the
 * required imports are added as a separate text edit (see [[JavaTypeShortener]]).
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

    enclosingClass(trees, cu).toList.flatMap { classPath =>
      trees.getElement(classPath) match {
        case typeElement: TypeElement
            if !typeElement.getModifiers.contains(Modifier.ABSTRACT) &&
              typeElement.getKind() != ElementKind.INTERFACE &&
              typeElement.getKind() != ElementKind.ANNOTATION_TYPE =>
          val abstractMethods =
            unimplementedAbstractMethods(task, typeElement)
          if (abstractMethods.isEmpty) Nil
          else {
            val classTree = classPath.getLeaf().asInstanceOf[ClassTree]
            val shortener = newShortener(cu, classTree)
            val bodyEdit = bodyEditFor(
              trees,
              cu,
              classTree,
              text,
              abstractMethods,
              shortener
            )
            // The body edit must be computed before reading the imports, since
            // rendering the stubs is what registers the needed imports.
            JavaAutoImportEditor
              .imports(text, shortener.newImports)
              .toList :+ bodyEdit
          }
        case _ => Nil
      }
    }
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
        (method, execType) <- (
          method,
          types.asMemberOf(declaredType, method)
        ) match {
          case (method: ExecutableElement, tpe: ExecutableType) =>
            Some((method, tpe))
          case _ => None
        }
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

  private def newShortener(
      cu: CompilationUnitTree,
      targetClass: ClassTree
  ): JavaTypeShortener = {
    val currentPackage =
      Option(cu.getPackageName()).map(_.toString()).getOrElse("")
    val imports = cu.getImports().asScala.toList.filterNot(_.isStatic())
    val existingImports = imports.collect {
      case imp if !imp.getQualifiedIdentifier().toString().endsWith(".*") =>
        val fqn = imp.getQualifiedIdentifier().toString()
        fqn.substring(fqn.lastIndexOf('.') + 1) -> fqn
    }.toMap
    val topLevelTypeNames = cu
      .getTypeDecls()
      .asScala
      .collect { case cls: ClassTree => cls.getSimpleName().toString() }
      .toSet
    val memberTypeNames = collectMemberTypeNames(targetClass)
    val declaredTypeNames = topLevelTypeNames ++ memberTypeNames
    new JavaTypeShortener(
      currentPackage,
      existingImports,
      declaredTypeNames
    )
  }

  /** Collects simple names of member types declared inside the given class. */
  private def collectMemberTypeNames(classTree: ClassTree): Set[String] =
    classTree
      .getMembers()
      .asScala
      .collect { case cls: ClassTree => cls.getSimpleName().toString() }
      .toSet

  private def bodyEditFor(
      trees: Trees,
      cu: CompilationUnitTree,
      classTree: ClassTree,
      text: String,
      methods: List[(ExecutableElement, ExecutableType)],
      shortener: JavaTypeShortener
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
          methodStub(method, execType, memberIndent, bodyIndent, shortener)
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
      bodyIndent: String,
      shortener: JavaTypeShortener
  ): String = {
    val signature = methodSignature(method, execType, shortener)
    s"""$memberIndent@Override
       |$memberIndent$signature {
       |$bodyIndent$placeholderBody
       |$memberIndent}""".stripMargin
  }

  private def methodSignature(
      method: ExecutableElement,
      execType: ExecutableType,
      shortener: JavaTypeShortener
  ): String = {
    val modifier =
      if (method.getModifiers().contains(Modifier.PUBLIC)) "public "
      else if (method.getModifiers().contains(Modifier.PROTECTED)) "protected "
      else ""
    val typeParams = typeParameters(method, shortener)
    val returnType = shortener.shorten(execType.getReturnType())
    val name = method.getSimpleName().toString()
    val params = parameters(method, execType, shortener)
    s"$modifier$typeParams$returnType $name($params)"
  }

  private def typeParameters(
      method: ExecutableElement,
      shortener: JavaTypeShortener
  ): String = {
    val typeParams = method.getTypeParameters().asScala.toList
    if (typeParams.isEmpty) ""
    else {
      val rendered =
        typeParams.map(shortener.renderTypeParameter).mkString(", ")
      s"<$rendered> "
    }
  }

  private def parameters(
      method: ExecutableElement,
      execType: ExecutableType,
      shortener: JavaTypeShortener
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
              s"${shortener.shorten(array.getComponentType())}... $name"
            case _ => s"${shortener.shorten(paramType)} $name"
          }
        } else s"${shortener.shorten(paramType)} $name"
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

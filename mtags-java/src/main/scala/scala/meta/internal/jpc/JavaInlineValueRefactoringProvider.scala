package scala.meta.internal.jpc

import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier

import scala.collection.mutable.ListBuffer

import scala.meta.internal.pc.Definition
import scala.meta.internal.pc.InlineValueProvider
import scala.meta.internal.pc.InlineValueProvider.Errors
import scala.meta.internal.pc.RangeOffset
import scala.meta.internal.pc.Reference
import scala.meta.pc.OffsetParams

import com.sun.source.tree.ArrayAccessTree
import com.sun.source.tree.ArrayTypeTree
import com.sun.source.tree.AssignmentTree
import com.sun.source.tree.BinaryTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.CompoundAssignmentTree
import com.sun.source.tree.ConditionalExpressionTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.InstanceOfTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.NewArrayTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TypeCastTree
import com.sun.source.tree.UnaryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

/**
 * Computes "inline value" refactoring edits for Java, backed by the Java
 * presentation compiler (the javac AST).
 *
 * Reference resolution is precise because javac resolves every identifier to
 * its [[Element]], so shadowing is handled for free. Inlining is restricted to
 * the scope of a single file:
 *   - local variables are inlined everywhere they are used and the declaration
 *     is removed,
 *   - `private` fields are treated the same way, since they can only be
 *     referenced from within the same file,
 *   - any other (potentially project-wide) field can only have the single
 *     reference under the cursor inlined, keeping the declaration in place.
 *
 * The actual text edit generation (bracket wrapping and removal of the
 * declaration) is delegated to the shared [[InlineValueProvider]].
 */
final class JavaInlineValueRefactoringProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams
) extends InlineValueProvider {

  private val sourceText: String = params.text()

  override val text: Array[Char] = sourceText.toCharArray

  override val position: l.Position =
    compiler.offsetToPosition(params.offset(), sourceText)

  override def defAndRefs(): Either[String, (Definition, List[Reference])] =
    compiler.nodeAtPosition(params, forReferences = true) match {
      case Some((compile, cursorPath)) =>
        val trees = Trees.instance(compile.task)
        val target = trees.getElement(cursorPath)
        if (isInlinable(target))
          computeEdits(trees, compile.cu, target, cursorPath)
        else Left(Errors.didNotFindDefinition)
      case None => Left(Errors.didNotFindDefinition)
    }

  private def computeEdits(
      trees: Trees,
      cu: CompilationUnitTree,
      target: Element,
      cursorPath: TreePath
  ): Either[String, (Definition, List[Reference])] = {
    val positions = trees.getSourcePositions()
    def startOf(tree: Tree): Int = positions.getStartPosition(cu, tree).toInt
    def endOf(tree: Tree): Int = positions.getEndPosition(cu, tree).toInt

    val (definitions, references) =
      occurrencesOf(target, trees, cu)
        .partition(_.getLeaf().getKind() == Tree.Kind.VARIABLE)

    // Inlining a value that is reassigned would leave a dangling assignment
    // target and change behavior, so it is never offered.
    if (references.exists(isWriteTarget)) Left(Errors.isReassigned)
    else
      definitions.headOption
        .map(_.getLeaf().asInstanceOf[VariableTree])
        .filter(_.getInitializer() != null) match {
        case None => Left(Errors.didNotFindDefinition)
        case Some(varTree) =>
          val init = varTree.getInitializer()
          val defStart = startOf(varTree)
          val rhsStart = startOf(init)
          val rhsEnd = endOf(init)
          // javac does not always record an end position for the declaration
          // itself, so the deletion range is derived from the (reliably
          // positioned) initializer expression instead.
          if (defStart < 0 || rhsStart < 0 || rhsEnd < rhsStart)
            Left(Errors.didNotFindDefinition)
          else {
            val nameLength = target.getSimpleName().toString().length()

            // A reference edit for a usage, or `None` for one we cannot safely
            // rewrite (a qualified access such as `this.field`).
            def reference(path: TreePath): Option[Reference] =
              path.getLeaf() match {
                case identifier: IdentifierTree if startOf(identifier) >= 0 =>
                  val start = startOf(identifier)
                  Some(
                    Reference(
                      toRange(start, start + nameLength),
                      parentOffsets = None,
                      referenceNeedsBrackets(path),
                      requiresCurlyBraces = false
                    )
                  )
                case _ => None
              }

            val onDefinition =
              cursorPath.getLeaf().getKind() == Tree.Kind.VARIABLE
            val canInlineAll = isLocal(target) || isPrivateField(target)

            if (onDefinition && !canInlineAll) Left(Errors.notLocal)
            else {
              // The declaration can be removed when every usage is inlined: when
              // the action is invoked on the declaration, or on the single usage
              // of a value that is local to the file.
              val removeDeclaration =
                canInlineAll && (onDefinition || references.lengthCompare(
                  1
                ) == 0)
              val edits =
                (if (removeDeclaration) references else List(cursorPath))
                  .map(reference)

              if (edits.contains(None)) Left(Errors.didNotFindReference)
              else {
                val defEnd = endWithSemicolon(rhsEnd)
                val rhsText = formatInitializer(varTree, init, rhsStart, rhsEnd)
                val definition = Definition(
                  toRange(defStart, defEnd),
                  rhsText,
                  RangeOffset(defStart, defEnd),
                  initializerNeedsBrackets(init),
                  requiresCurlyBraces = false,
                  shouldBeRemoved = removeDeclaration
                )
                Right((definition, edits.flatten))
              }
            }
          }
      }
  }

  private def occurrencesOf(
      target: Element,
      trees: Trees,
      cu: CompilationUnitTree
  ): List[TreePath] = {
    val scanner = new OccurrenceScanner(target, trees)
    scanner.scan(cu, null)
    scanner.occurrences.toList
  }

  /** Whether `path` is the target of an assignment, `+=`, `++` or `--`. */
  private def isWriteTarget(path: TreePath): Boolean = {
    val node = path.getLeaf()
    Option(path.getParentPath()).map(_.getLeaf()).exists {
      case assign: AssignmentTree => assign.getVariable() == node
      case compound: CompoundAssignmentTree => compound.getVariable() == node
      case unary: UnaryTree =>
        unary.getExpression() == node && isIncrementOrDecrement(unary.getKind())
      case _ => false
    }
  }

  private def isIncrementOrDecrement(kind: Tree.Kind): Boolean =
    kind == Tree.Kind.POSTFIX_INCREMENT ||
      kind == Tree.Kind.POSTFIX_DECREMENT ||
      kind == Tree.Kind.PREFIX_INCREMENT ||
      kind == Tree.Kind.PREFIX_DECREMENT

  /** Extend the declaration range to include the terminating `;`. */
  private def endWithSemicolon(end: Int): Int = {
    var index = end
    while (index < sourceText.length && sourceText.charAt(index).isWhitespace)
      index += 1
    if (index < sourceText.length && sourceText.charAt(index) == ';') index + 1
    else end
  }

  private def toRange(start: Int, end: Int): l.Range =
    new l.Range(
      compiler.offsetToPosition(start, sourceText),
      compiler.offsetToPosition(end, sourceText)
    )

  private def isInlinable(element: Element): Boolean =
    element != null && {
      element.getKind() == ElementKind.LOCAL_VARIABLE ||
      element.getKind() == ElementKind.FIELD
    }

  private def isLocal(element: Element): Boolean =
    element.getKind() == ElementKind.LOCAL_VARIABLE

  private def isPrivateField(element: Element): Boolean =
    element.getKind() == ElementKind.FIELD &&
      element.getModifiers().contains(Modifier.PRIVATE)

  private def initializerNeedsBrackets(init: Tree): Boolean =
    init match {
      case _: BinaryTree | _: ConditionalExpressionTree | _: AssignmentTree |
          _: CompoundAssignmentTree | _: InstanceOfTree |
          _: LambdaExpressionTree | _: TypeCastTree =>
        true
      case _ => false
    }

  private def formatInitializer(
      varTree: VariableTree,
      init: Tree,
      rhsStart: Int,
      rhsEnd: Int
  ): String = {
    val rawText = sourceText.substring(rhsStart, rhsEnd)
    init match {
      case arr: NewArrayTree
          if arr.getDimensions().isEmpty && arr.getInitializers() != null =>
        extractArrayType(varTree.getType()) match {
          case Some(elementType) => s"new $elementType[]$rawText"
          case None => rawText
        }
      case _ => rawText
    }
  }

  private def extractArrayType(typeTree: Tree): Option[String] =
    typeTree match {
      case arr: ArrayTypeTree => Some(arr.getType().toString())
      case _ => None
    }

  private def referenceNeedsBrackets(path: TreePath): Boolean = {
    val node = path.getLeaf()
    Option(path.getParentPath()).map(_.getLeaf()) match {
      case Some(binary: BinaryTree) =>
        binary.getRightOperand() == node ||
        (binary.getLeftOperand() == node && hasHigherPrecedenceThanAdditive(
          binary.getKind()
        ))
      case Some(select: MemberSelectTree) => select.getExpression() == node
      case Some(access: ArrayAccessTree) => access.getExpression() == node
      case Some(_: UnaryTree) => true
      case Some(_: TypeCastTree) => true
      case _ => false
    }
  }

  private def hasHigherPrecedenceThanAdditive(kind: Tree.Kind): Boolean =
    kind == Tree.Kind.MULTIPLY ||
      kind == Tree.Kind.DIVIDE ||
      kind == Tree.Kind.REMAINDER

  /** Collects every tree resolving to `target` (the declaration and usages). */
  private final class OccurrenceScanner(
      target: Element,
      trees: Trees
  ) extends TreePathScanner[Void, Void] {
    val occurrences: ListBuffer[TreePath] = ListBuffer.empty

    override def scan(tree: Tree, p: Void): Void = {
      if (tree != null) {
        val path = new TreePath(getCurrentPath(), tree)
        if (target.equals(trees.getElement(path))) occurrences += path
      }
      super.scan(tree, p)
    }
  }
}

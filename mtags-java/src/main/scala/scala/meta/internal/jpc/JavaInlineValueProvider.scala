package scala.meta.internal.jpc

import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.pc.RangeParams

import com.sun.source.tree.ArrayAccessTree
import com.sun.source.tree.BlockTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.InstanceOfTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TypeCastTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

final class JavaInlineValueProvider(
    compiler: JavaMetalsCompiler,
    params: RangeParams,
    stoppedLocationOffset: Int
) {

  def inlineValues(): List[l.InlineValue] = {
    params.checkCanceled()
    val compile = compiler.compilationTask(params).withAnalyzePhase()
    params.checkCanceled()
    val trees = Trees.instance(compile.task)
    val requestEnd = math.min(params.endOffset(), stoppedLocationOffset)
    val scanner = new InlineValueScanner(
      trees,
      compile.cu,
      params.offset(),
      requestEnd
    )
    scanner.scan(compile.cu, ())
    scanner.result()
  }

  private object JavaSafeExpression {
    def isEvaluatable(tree: Tree): Boolean =
      tree match {
        case _: IdentifierTree =>
          true
        case select: MemberSelectTree =>
          isReceiver(select.getExpression())
        case access: ArrayAccessTree =>
          isEvaluatable(access.getExpression()) &&
          isIndex(access.getIndex())
        case _ =>
          false
      }

    def isReceiver(tree: Tree): Boolean =
      tree match {
        case _: IdentifierTree =>
          true
        case _: LiteralTree =>
          true
        case select: MemberSelectTree =>
          isReceiver(select.getExpression())
        case access: ArrayAccessTree =>
          isEvaluatable(access)
        case _ =>
          false
      }

    def isIndex(tree: Tree): Boolean =
      tree match {
        case _: IdentifierTree =>
          true
        case _: LiteralTree =>
          true
        case select: MemberSelectTree =>
          isReceiver(select.getExpression())
        case _ =>
          false
      }
  }

  private final class InlineValueEmitter(
      cu: CompilationUnitTree,
      requestStart: Int,
      requestEnd: Int
  ) {
    private val seen = mutable.Set.empty[(Int, Int)]
    private val values = List.newBuilder[l.InlineValue]
    private val text = cu.getSourceFile().getCharContent(true).toString()

    def emitVariable(name: String, start: Int, end: Int): Unit =
      addValue(start, end) { range =>
        new l.InlineValue(new l.InlineValueVariableLookup(range, true, name))
      }

    def emitQualified(
        name: String,
        qualifier: String,
        start: Int,
        end: Int
    ): Unit =
      addValue(start, end) { range =>
        new l.InlineValue(
          new l.InlineValueEvaluatableExpression(range, s"$qualifier.$name")
        )
      }

    def emitExpression(start: Int, end: Int): Unit =
      addValue(start, end) { range =>
        new l.InlineValue(
          new l.InlineValueEvaluatableExpression(
            range,
            text.substring(start, end)
          )
        )
      }

    def result(): List[l.InlineValue] = values.result()

    private def addValue(start: Int, end: Int)(
        build: l.Range => l.InlineValue
    ): Unit = {
      if (
        start >= requestStart &&
        end <= requestEnd &&
        start < end &&
        seen.add(start -> end)
      ) {
        values += build(
          Positions.toLspRange(cu.getLineMap(), start, end, text)
        )
      }
    }
  }

  private final class TreeBounds(
      trees: Trees,
      currentCu: () => CompilationUnitTree
  ) {
    def of(node: Tree): (Int, Int) = (startOf(node), endOf(node))

    def startOf(node: Tree): Int =
      trees.getSourcePositions().getStartPosition(currentCu(), node).toInt

    def endOf(node: Tree): Int =
      trees.getSourcePositions().getEndPosition(currentCu(), node).toInt
  }

  private final class InlineValueScanner(
      trees: Trees,
      cu: CompilationUnitTree,
      requestStart: Int,
      requestEnd: Int
  ) extends TreePathScanner[Unit, Unit] {
    private val emitter =
      new InlineValueEmitter(cu, requestStart, requestEnd)
    private val bounds =
      new TreeBounds(trees, () => getCurrentPath().getCompilationUnit)

    override def visitCompilationUnit(
        node: CompilationUnitTree,
        p: Unit
    ): Unit = {
      scan(node.getTypeDecls(), p)
    }

    override def visitClass(node: ClassTree, p: Unit): Unit = {
      scan(node.getMembers(), p)
    }

    override def visitMethod(node: MethodTree, p: Unit): Unit = {
      scan(node.getReceiverParameter(), p)
      scan(node.getParameters(), p)
      scan(node.getBody(), p)
    }

    override def visitBlock(node: BlockTree, p: Unit): Unit = {
      super.visitBlock(node, p)
    }

    override def visitForLoop(node: ForLoopTree, p: Unit): Unit = {
      super.visitForLoop(node, p)
    }

    override def visitEnhancedForLoop(
        node: EnhancedForLoopTree,
        p: Unit
    ): Unit = {
      super.visitEnhancedForLoop(node, p)
    }

    override def visitCatch(node: CatchTree, p: Unit): Unit = {
      super.visitCatch(node, p)
    }

    override def visitLambdaExpression(
        node: LambdaExpressionTree,
        p: Unit
    ): Unit = {
      scan(node.getParameters(), p)
      scan(node.getBody(), p)
    }

    override def visitVariable(node: VariableTree, p: Unit): Unit = {
      val element = trees.getElement(getCurrentPath())
      val name = node.getName().toString()
      if (isVariableLike(element)) {
        emitVariableName(node, name)
      } else if (isField(element) && node.getInitializer() != null) {
        emitFieldName(node, element, name)
      }
      scan(node.getNameExpression(), p)
      scan(node.getInitializer(), p)
    }

    override def visitNewClass(node: NewClassTree, p: Unit): Unit = {
      scan(node.getEnclosingExpression(), p)
      scan(node.getArguments(), p)
      scan(node.getClassBody(), p)
    }

    override def visitTypeCast(node: TypeCastTree, p: Unit): Unit = {
      scan(node.getExpression(), p)
    }

    override def visitInstanceOf(node: InstanceOfTree, p: Unit): Unit = {
      scan(node.getExpression(), p)
      scan(node.getPattern(), p)
    }

    override def visitIdentifier(node: IdentifierTree, p: Unit): Unit = {
      if (!isSubExpressionOfOuter()) {
        val (start, end) = bounds.of(node)
        emitElementReference(
          node.getName().toString(),
          trees.getElement(getCurrentPath()),
          start,
          end
        )
      }
      super.visitIdentifier(node, p)
    }

    override def visitMemberSelect(node: MemberSelectTree, p: Unit): Unit = {
      if (!isSubExpressionOfOuter() && JavaSafeExpression.isEvaluatable(node)) {
        val (start, end) = bounds.of(node)
        emitter.emitExpression(start, end)
      }
      super.visitMemberSelect(node, p)
    }

    override def visitArrayAccess(node: ArrayAccessTree, p: Unit): Unit = {
      if (!isSubExpressionOfOuter() && JavaSafeExpression.isEvaluatable(node)) {
        val (start, end) = bounds.of(node)
        emitter.emitExpression(start, end)
      }
      super.visitArrayAccess(node, p)
    }

    override def visitMethodInvocation(
        node: MethodInvocationTree,
        p: Unit
    ): Unit = {
      super.visitMethodInvocation(node, p)
    }

    def result(): List[l.InlineValue] = emitter.result()

    private def emitElementReference(
        name: String,
        element: Element,
        start: Int,
        end: Int
    ): Unit = {
      if (isVariableLike(element)) {
        emitter.emitVariable(name, start, end)
      } else if (isField(element)) {
        emitFieldReference(element, name, start, end)
      } else {
        emitter.emitExpression(start, end)
      }
    }

    private def emitFieldReference(
        element: Element,
        name: String,
        start: Int,
        end: Int
    ): Unit =
      if (isStatic(element)) {
        enclosingTypeName(element).foreach { owner =>
          emitter.emitQualified(name, owner, start, end)
        }
      } else {
        emitter.emitQualified(name, "this", start, end)
      }

    private def emitVariableName(node: VariableTree, name: String): Unit =
      locateDeclaredName(node, name).foreach { start =>
        emitter.emitVariable(name, start, start + name.length())
      }

    private def emitFieldName(
        node: VariableTree,
        element: Element,
        name: String
    ): Unit =
      locateDeclaredName(node, name).foreach { start =>
        val end = start + name.length()
        emitFieldReference(element, name, start, end)
      }

    private def locateDeclaredName(
        node: VariableTree,
        name: String
    ): Option[Int] = {
      val (treeStart, treeEnd) = bounds.of(node)
      if (treeStart < 0 || treeEnd < treeStart) None
      else {
        val typeEnd =
          Option(node.getType()).map(bounds.endOf).filter(_ >= 0)
        val initializerStart =
          Option(node.getInitializer()).map(bounds.startOf).filter(_ >= 0)
        val searchStart = typeEnd.getOrElse(treeStart)
        val searchEnd = initializerStart.getOrElse(treeEnd)
        if (searchEnd <= searchStart) None
        else {
          val text = cu.getSourceFile().getCharContent(true).toString()
          findNameStart(text, name, searchStart, searchEnd)
        }
      }
    }

    private def findNameStart(
        text: String,
        name: String,
        treeStart: Int,
        treeEnd: Int
    ): Option[Int] = {
      var index = treeStart
      var result = Option.empty[Int]
      while (
        result.isEmpty &&
        index >= 0 &&
        index < treeEnd &&
        index < text.length
      ) {
        val found = text.indexOf(name, index)
        if (found < 0 || found >= treeEnd) index = treeEnd
        else if (isIdentifierAt(text, found, found + name.length())) {
          result = Some(found)
        } else index = found + name.length()
      }
      result
    }

    private def isIdentifierAt(text: String, start: Int, end: Int): Boolean =
      (start == 0 || !Character.isJavaIdentifierPart(text.charAt(start - 1))) &&
        (end >= text.length || !Character.isJavaIdentifierPart(
          text.charAt(end)
        ))

    private def isSubExpressionOfOuter(): Boolean = {
      val leaf = getCurrentPath().getLeaf()
      Option(getCurrentPath().getParentPath())
        .exists { parent =>
          parent.getLeaf() match {
            case select: MemberSelectTree => select.getExpression() == leaf
            case access: ArrayAccessTree => access.getExpression() == leaf
            case call: MethodInvocationTree => call.getMethodSelect() == leaf
            case _ => false
          }
        }
    }

    private def isVariableLike(element: Element): Boolean =
      Option(element).exists { element =>
        Set(
          ElementKind.LOCAL_VARIABLE,
          ElementKind.PARAMETER,
          ElementKind.EXCEPTION_PARAMETER,
          ElementKind.RESOURCE_VARIABLE,
          ElementKind.BINDING_VARIABLE
        ).contains(element.getKind())
      }

    private def isField(element: Element): Boolean =
      Option(element).exists(_.getKind() == ElementKind.FIELD)

    private def isStatic(element: Element): Boolean =
      Option(element).exists(_.getModifiers().asScala.contains(Modifier.STATIC))

    private def enclosingTypeName(element: Element): Option[String] =
      Iterator
        .iterate(Option(element).flatMap(e => Option(e.getEnclosingElement())))(
          _.flatMap(e => Option(e.getEnclosingElement()))
        )
        .takeWhile(_.nonEmpty)
        .flatten
        .collectFirst { case t: TypeElement =>
          t.getQualifiedName().toString()
        }
  }
}

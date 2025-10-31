package scala.meta.internal.pc

import scala.collection.immutable.Nil

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ErroneousTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.MemberReferenceTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees

class JavaTreeScanner(
    task: JavacTask,
    var root: CompilationUnitTree
) extends TreePathScanner[TreePath, CursorPosition] {

  var lastVisitedParentTrees: List[TreePath] = Nil

  override def visitCompilationUnit(
      t: CompilationUnitTree,
      p: CursorPosition
  ): TreePath = {
    root = t
    reduce(super.visitCompilationUnit(t, p), getCurrentPath)
  }

  override def visitIdentifier(
      node: IdentifierTree,
      p: CursorPosition
  ): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getStartPosition(root, node)
    val end = pos.getEndPosition(root, node)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
    }
    super.visitIdentifier(node, p)
  }

  override def visitMemberSelect(
      node: MemberSelectTree,
      p: CursorPosition
  ): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getEndPosition(root, node.getExpression) + 1
    val end = pos.getEndPosition(root, node)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
    }
    super.visitMemberSelect(node, p)

  }

  override def visitMemberReference(
      node: MemberReferenceTree,
      p: CursorPosition
  ): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getEndPosition(root, node.getQualifierExpression) + 2
    val end = pos.getEndPosition(root, node)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
    }
    super.visitMemberReference(node, p)
  }

  override def visitImport(node: ImportTree, p: CursorPosition): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getStartPosition(root, node.getQualifiedIdentifier)
    val end = pos.getEndPosition(root, node.getQualifiedIdentifier)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
    }
    super.visitImport(node, p)
  }

  override def visitErroneous(
      node: ErroneousTree,
      p: CursorPosition
  ): TreePath =
    scan(node.getErrorTrees, p)

  override def visitVariable(
      node: VariableTree,
      p: CursorPosition
  ): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getStartPosition(root, node)
    val end = pos.getEndPosition(root, node)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
    }
    super.visitVariable(node, p)
  }

  override def visitClass(node: ClassTree, p: CursorPosition): TreePath = {
    visitNode(node, p, super.visitClass)
  }

  override def visitMethod(node: MethodTree, p: CursorPosition): TreePath = {

    visitNode(node, p, super.visitMethod)
  }

  override def visitNewClass(
      node: NewClassTree,
      p: CursorPosition
  ): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getStartPosition(root, node.getIdentifier)
    val end = pos.getEndPosition(root, node.getIdentifier)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
      super.visitNewClass(node, p)
    } else {
      super.visitNewClass(node, p)
      visitNode(node, p, super.visitNewClass)
    }
  }

  override def reduce(a: TreePath, b: TreePath): TreePath = {
    if (a != null) return a
    b
  }

  def getEndPosition(node: Tree): Long = {
    val pos = Trees.instance(task).getSourcePositions

    pos.getEndPosition(root, node)
  }

  private def visitNode[N <: Tree](
      node: N,
      p: CursorPosition,
      traverse: (N, CursorPosition) => TreePath
  ): TreePath = {
    val pos = Trees.instance(task).getSourcePositions
    val start = pos.getStartPosition(root, node)
    val end = pos.getEndPosition(root, node)

    if (start <= p.start && p.end <= end) {
      lastVisitedParentTrees = getCurrentPath :: lastVisitedParentTrees
    }

    traverse(node, p)
  }
}

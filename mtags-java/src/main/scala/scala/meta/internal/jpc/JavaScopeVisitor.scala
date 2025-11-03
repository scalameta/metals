package scala.meta.internal.jpc

import javax.lang.model.`type`.DeclaredType
import javax.lang.model.element.Element
import javax.lang.model.util.Elements

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.IterableHasAsScala

import com.sun.source.tree.Scope
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees

object JavaScopeVisitor {

  @tailrec
  private def unfurlScope(scope: Scope, acc: List[Scope]): List[Scope] = {
    if (scope == null) acc.reverse
    else unfurlScope(scope.getEnclosingScope, scope :: acc)
  }

  def scopeMembers(
      task: JavacTask,
      scope: Scope,
      trees: Trees,
      path: TreePath
  ): List[Element] = {
    val scopes = unfurlScope(scope, Nil)
    val elements = task.getElements

    val result = List.newBuilder[Element]

    result ++= classMembers(scope, elements, trees, path)
    result ++= (for {
      curScope <- scopes.iterator
      member <- curScope.getLocalElements.asScala
    } yield member)

    result.result()
  }

  private def classMembers(
      scope: Scope,
      elements: Elements,
      trees: Trees,
      path: TreePath
  ): List[Element] = {
    for {
      enclosingClass <- Option(scope.getEnclosingClass()).iterator
      declaredType <- enclosingClass.asType match {
        case dt: DeclaredType => List(dt)
        case _ => Nil
      }
      scopeForAccess = trees.getScope(path)
      member <- elements.getAllMembers(enclosingClass).asScala
      if trees.isAccessible(scopeForAccess, member, declaredType)
    } yield member
  }.toList
}

package scala.meta.internal.jpc

import javax.lang.model.element.Element
import javax.lang.model.element.Modifier
import javax.lang.model.util.Elements

import scala.annotation.tailrec
import scala.jdk.CollectionConverters.IterableHasAsScala

import com.sun.source.tree.Scope
import com.sun.source.util.JavacTask

object JavaScopeVisitor {

  @tailrec
  private def unfurlScope(scope: Scope, acc: List[Scope]): List[Scope] = {
    if (scope == null) acc.reverse
    else unfurlScope(scope.getEnclosingScope, scope :: acc)
  }

  def scopeMembers(task: JavacTask, scope: Scope): List[Element] = {
    val scopes = unfurlScope(scope, Nil)
    val elements = task.getElements

    (for {
      curScope <- scopes
      member <- curScope.getLocalElements.asScala
      allClassMembers = classMembers(scope, elements)
    } yield member :: allClassMembers).flatten
  }

  private def classMembers(scope: Scope, elements: Elements): List[Element] = {
    if (scope.getEnclosingClass == null) Nil
    else {
      val typeElement = scope.getEnclosingClass

      elements
        .getAllMembers(typeElement)
        .asScala
        .filterNot(e =>
          e.getModifiers.contains(Modifier.PRIVATE) || e.getModifiers
            .contains(Modifier.PROTECTED)
        )
        .toList
    }
  }
}

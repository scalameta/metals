package scala.meta.internal.pc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement

import scala.annotation.tailrec
import scala.jdk.CollectionConverters._

import scala.meta.pc.OffsetParams

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree.Kind._
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat

class JavaCompletionProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams,
    isCompletionSnippetsEnabled: Boolean
) {

  def completions(): CompletionList = {
    val nextIsWhitespace =
      params.text().charAt(params.offset()).isWhitespace
    val textWithSemicolon =
      if (nextIsWhitespace)
        params.text().substring(0, params.offset()) +
          ";" +
          params.text().substring(params.offset())
      else params.text()
    val task: JavacTask =
      compiler.compilationTask(textWithSemicolon, params.uri())
    val scanner = compiler.scanner(task)
    val position =
      CursorPosition(params.offset(), params.offset(), params.offset())
    val node = compiler.compilerTreeNode(scanner, position)

    node match {
      case Some(n) =>
        val list =
          n.getLeaf.getKind match {
            case MEMBER_SELECT => completeMemberSelect(task, n)
            case IDENTIFIER => completeIdentifier(task, n)
            case _ => new CompletionList()
          }

        val keywordsCompletion = keywords(n)
        val resultList =
          (list.getItems.asScala ++ keywordsCompletion.getItems.asScala).distinct
            .sorted(ordering)
            .asJava

        new CompletionList(resultList)
      case None => new CompletionList()
    }
  }

  private def ordering: Ordering[CompletionItem] = {
    val identifier = extractIdentifier.toLowerCase

    new Ordering[CompletionItem] {
      def score(item: CompletionItem): Int = {
        val name = item.getLabel.toLowerCase

        if (name.startsWith(identifier)) 0
        else if (name.contains(identifier)) 1
        else 2
      }

      override def compare(i1: CompletionItem, i2: CompletionItem): Int = {
        java.lang.Integer.compare(score(i1), score(i2))
      }
    }

  }

  private def completeMemberSelect(
      task: JavacTask,
      path: TreePath
  ): CompletionList = {
    val typeAnalyzer = new JavaTypeAnalyzer(task)
    val select = path.getLeaf.asInstanceOf[MemberSelectTree]
    val newPath = new TreePath(path, select.getExpression)
    val memberType = typeAnalyzer.typeMirror(newPath)

    memberType match {
      case dt: DeclaredType => completeDeclaredType(task, dt)
      case _: ArrayType => completeArrayType()
      case tv: TypeVariable => completeTypeVariable(task, tv)
      case _ => new CompletionList()
    }
  }

  private def completeIdentifier(
      task: JavacTask,
      path: TreePath
  ): CompletionList =
    new CompletionList(completeFromScope(task, path).asJava)

  private def completeFromScope(
      task: JavacTask,
      path: TreePath
  ): List[CompletionItem] = {
    val trees = Trees.instance(task)
    val scope = trees.getScope(path)

    val scopeCompletion = JavaScopeVisitor.scopeMembers(task, scope)
    val identifier = extractIdentifier

    scopeCompletion
      .map(completionItem)
      .filter(item => CompletionFuzzy.matches(identifier, item.getLabel))
  }

  private def completeDeclaredType(
      task: JavacTask,
      declaredType: DeclaredType
  ): CompletionList = {
    val members = task.getElements
      .getAllMembers(declaredType.asElement().asInstanceOf[TypeElement])
      .asScala
      .toList

    val identifier = extractIdentifier

    val completionItems = members
      .filter(member =>
        CompletionFuzzy.matches(identifier, member.getSimpleName.toString)
      )
      .map(completionItem)

    new CompletionList(completionItems.asJava)
  }

  private def extractIdentifier: String = {
    val start = inferIdentStart(params.offset(), params.text())
    val end = params.offset()

    params.text().substring(start, end)
  }

  private def keywords(path: TreePath): CompletionList = {
    val identifier = extractIdentifier
    val level = keywordLevel(path)

    val completionItems =
      JavaKeyword.all
        .collect {
          case keyword
              if keyword.level == level && CompletionFuzzy.matches(
                identifier,
                keyword.name
              ) =>
            keyword.name
        }
        .map { keyword =>
          val item = new CompletionItem(keyword)
          item.setKind(CompletionItemKind.Keyword)

          item
        }

    new CompletionList(completionItems.asJava)
  }

  @tailrec
  private def keywordLevel(path: TreePath): JavaKeyword.Level = {
    if (path == null) JavaKeyword.TopLevel
    else {
      path.getLeaf match {
        case _: MethodTree => JavaKeyword.MethodLevel
        case _: ClassTree => JavaKeyword.ClassLevel
        case _: CompilationUnitTree => JavaKeyword.TopLevel
        case _ => keywordLevel(path.getParentPath)
      }
    }
  }

  private def completeArrayType(): CompletionList = {
    val identifier = extractIdentifier
    if (CompletionFuzzy.matches(identifier, "length")) {
      val item = new CompletionItem("length")
      item.setKind(CompletionItemKind.Keyword)

      new CompletionList(
        List(item).asJava
      )
    } else {
      new CompletionList()
    }

  }

  @tailrec
  private def completeTypeVariable(
      task: JavacTask,
      typeVariable: TypeVariable
  ): CompletionList = {
    typeVariable.getUpperBound match {
      case dt: DeclaredType => completeDeclaredType(task, dt)
      case tv: TypeVariable => completeTypeVariable(task, tv)
      case _ => new CompletionList()
    }
  }

  private def inferIdentStart(pos: Int, text: String): Int = {
    var i = pos - 1
    while (i >= 0 && Character.isJavaIdentifierPart(text.charAt(i))) {
      i -= 1
    }
    i + 1
  }

  private def completionItem(element: Element): CompletionItem = {
    val simpleName = element.getSimpleName.toString

    val (label, insertText) = element match {
      case e: ExecutableElement
          if isCompletionSnippetsEnabled && e.getParameters.size() > 0 =>
        (JavaLabels.executableLabel(e), s"$simpleName($$0)")
      case e: ExecutableElement =>
        (JavaLabels.executableLabel(e), s"$simpleName()")
      case _ => (simpleName, simpleName)
    }

    val item = new CompletionItem(label)

    if (isCompletionSnippetsEnabled)
      item.setInsertTextFormat(InsertTextFormat.Snippet)

    item.setInsertText(insertText)

    val kind = completionKind(element.getKind)
    kind.foreach(item.setKind)

    val detail = element match {
      case v: VariableElement => JavaLabels.typeLabel(v.asType())
      case e: ExecutableElement => JavaLabels.typeLabel(e.asType())
      case t: TypeElement => JavaLabels.typeLabel(t.asType())
      case _ => ""
    }

    item.setDetail(detail)

    item
  }

  private def completionKind(k: ElementKind): Option[CompletionItemKind] =
    k match {
      case ElementKind.CLASS => Some(CompletionItemKind.Class)
      case ElementKind.ENUM => Some(CompletionItemKind.Enum)
      case ElementKind.ANNOTATION_TYPE => Some(CompletionItemKind.Interface)
      case ElementKind.INTERFACE => Some(CompletionItemKind.Interface)
      case ElementKind.CONSTRUCTOR => Some(CompletionItemKind.Constructor)
      case ElementKind.TYPE_PARAMETER => Some(CompletionItemKind.TypeParameter)
      case ElementKind.FIELD => Some(CompletionItemKind.Field)
      case ElementKind.PACKAGE => Some(CompletionItemKind.Module)
      case ElementKind.LOCAL_VARIABLE => Some(CompletionItemKind.Variable)
      case ElementKind.RESOURCE_VARIABLE => Some(CompletionItemKind.Variable)
      case ElementKind.PARAMETER => Some(CompletionItemKind.Property)
      case ElementKind.METHOD => Some(CompletionItemKind.Method)
      case _ => None
    }

}

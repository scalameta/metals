package scala.meta.internal.pc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
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
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.jsonrpc.messages.Either

class JavaCompletionProvider(
    compiler: JavaMetalsGlobal,
    params: OffsetParams,
    isCompletionSnippetsEnabled: Boolean,
    buildTargetIdentifier: String
) {

  lazy val identifier = extractIdentifier.toLowerCase
  def completions(): CompletionList = {
    val nextIsWhitespace =
      if (params.offset() < params.text().length())
        params.text().charAt(params.offset()).isWhitespace
      else false
    val textWithSemicolon =
      if (nextIsWhitespace)
        params.text().substring(0, params.offset()) +
          ";" +
          params.text().substring(params.offset())
      else params.text()
    val task: JavacTask =
      compiler.compilationTask(textWithSemicolon, params.uri())
    val scanner = JavaMetalsGlobal.scanner(task)
    val position =
      CursorPosition(params.offset(), params.offset(), params.offset())
    val node = compiler.compilerTreeNode(scanner, position)

    node match {
      case Some(n) =>
        val items = n.getLeaf.getKind match {
          case MEMBER_SELECT => completeMemberSelect(task, n).distinct
          case IDENTIFIER =>
            val scope = completeIdentifier(task, n)
            val scopeLabels = scope.map(_.getLabel).toSet
            val symbols = completeSymbolSearch(task, scanner.root)
              .filterNot(i => scopeLabels(i.getLabel))
            val sorted = (scope ++ symbols).distinct
              .sortBy(item => identifierScore(item.getLabel)) ++ keywords(n)
            sorted
          case _ => keywords(n)
        }
        new CompletionList(items.asJava)
      case None => new CompletionList()
    }
  }

  private def identifierScore(element: Element): Int = {
    identifierScore(element.getSimpleName().toString())
  }

  private def identifierScore(name: String): Int = {
    val lower = name.toLowerCase()
    lower.indexOf(identifier) match {
      case 0 => 0
      case -1 => 2
      case _ => 1
    }
  }

  private def memberScore(element: Element, containingElement: Element): Int = {
    val idScore = identifierScore(element)
    val memberScore =
      if (element.getEnclosingElement() == containingElement) 0 else 1
    idScore << 1 | memberScore
  }

  private def completeMemberSelect(
      task: JavacTask,
      path: TreePath
  ): List[CompletionItem] = {
    val typeAnalyzer = new JavaTypeAnalyzer(task)
    val select = path.getLeaf.asInstanceOf[MemberSelectTree]
    val newPath = new TreePath(path, select.getExpression)
    val memberType = typeAnalyzer.typeMirror(newPath)

    val trees = Trees.instance(task)
    val exprElem = trees.getElement(newPath)

    val isStaticContext =
      exprElem != null && (exprElem.getKind match {
        case ElementKind.CLASS | ElementKind.INTERFACE | ElementKind.ENUM |
            ElementKind.ANNOTATION_TYPE | ElementKind.TYPE_PARAMETER =>
          true
        case _ => false
      })

    memberType match {
      case dt: DeclaredType => completeDeclaredType(task, dt, isStaticContext)
      case _: ArrayType => completeArrayType()
      case tv: TypeVariable => completeTypeVariable(task, tv)
      case _ => Nil
    }
  }

  private def completeIdentifier(
      task: JavacTask,
      path: TreePath
  ): List[CompletionItem] =
    completeFromScope(task, path)

  private def completeFromScope(
      task: JavacTask,
      path: TreePath
  ): List[CompletionItem] = {
    val trees = Trees.instance(task)
    val scope = trees.getScope(path)

    val scopeCompletion = JavaScopeVisitor.scopeMembers(task, scope)
    val identifier = extractIdentifier

    scopeCompletion
      .sortBy(el => identifierScore(el))
      .map(completionItem)
      .filter(item => CompletionFuzzy.matches(identifier, item.getLabel))
  }

  private def completeDeclaredType(
      task: JavacTask,
      declaredType: DeclaredType,
      isStaticContext: Boolean = false
  ): List[CompletionItem] = {
    // constructors cannot be invoked as members
    val bannedKinds = Set(
      ElementKind.CONSTRUCTOR,
      ElementKind.STATIC_INIT,
      ElementKind.INSTANCE_INIT
    )
    val declaredElement = declaredType.asElement()
    val members = task.getElements
      .getAllMembers(declaredElement.asInstanceOf[TypeElement])
      .asScala
      .toList

    val identifier = extractIdentifier

    val completionItems = members
      .filter { member =>
        val isMatches = CompletionFuzzy.matches(
          identifier,
          member.getSimpleName.toString
        )
        val isAllowedKind = !bannedKinds(member.getKind())
        val isStaticMember = member.getModifiers.contains(Modifier.STATIC)

        isMatches && isAllowedKind && (isStaticMember || !isStaticContext)
      }
      .sortBy { element =>
        memberScore(element, declaredElement)
      }
      .map(completionItem)
    completionItems
  }

  private def extractIdentifier: String = {
    val start = inferIdentStart(params.offset(), params.text())
    val end = params.offset()

    params.text().substring(start, end)
  }

  private def keywords(path: TreePath): List[CompletionItem] = {
    val identifier = extractIdentifier
    val level = keywordLevel(path)

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

  private def completeArrayType(): List[CompletionItem] = {
    val identifier = extractIdentifier
    if (CompletionFuzzy.matches(identifier, "length")) {
      val item = new CompletionItem("length")
      item.setKind(CompletionItemKind.Keyword)
      List(item)
    } else {
      Nil
    }

  }

  @tailrec
  private def completeTypeVariable(
      task: JavacTask,
      typeVariable: TypeVariable
  ): List[CompletionItem] = {
    typeVariable.getUpperBound match {
      case dt: DeclaredType => completeDeclaredType(task, dt)
      case tv: TypeVariable => completeTypeVariable(task, tv)
      case _ => Nil
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

    val start = inferIdentStart(params.offset(), params.text())
    val end = params.offset()
    val range = new Range(
      compiler.offsetToPosition(start, params.text()),
      compiler.offsetToPosition(end, params.text())
    )
    val textEdit = new TextEdit(range, insertText)
    item.setTextEdit(Either.forLeft(textEdit))

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

  private def autoImportPosition(
      task: JavacTask,
      root: CompilationUnitTree
  ): Position = {
    val sourcePositions = Trees.instance(task).getSourcePositions()
    val packageName = root.getPackageName
    if (packageName != null) {
      val end = sourcePositions.getEndPosition(root, packageName)
      // Scan for semicolon
      val text = root.getSourceFile.getCharContent(true)
      var i = end.toInt
      while (i < text.length && text.charAt(i) != ';') {
        i += 1
      }
      if (i < text.length) {
        // Found semicolon, move past it and whitespace/newlines
        i += 1
        while (i < text.length && Character.isWhitespace(text.charAt(i))) {
          i += 1
        }
        compiler.offsetToPosition(i, text.toString)
      } else {
        new Position(0, 0)
      }
    } else {
      new Position(0, 0)
    }
  }

  private def completeSymbolSearch(
      task: JavacTask,
      root: CompilationUnitTree
  ): List[CompletionItem] = {
    val identifier = extractIdentifier
    val result = List.newBuilder[CompletionItem]
    val visitor = new JavaClassVisitor(
      task.getElements,
      element => {
        val simpleName = element.getSimpleName.toString
        if (CompletionFuzzy.matches(identifier, simpleName)) {
          val item = completionItem(element)
          val pos = autoImportPosition(task, root)
          val className = element.toString
          val importText = s"import $className;\n"
          val edit = new TextEdit(new Range(pos, pos), importText)
          item.setAdditionalTextEdits(List(edit).asJava)

          result += item
          true
        } else false
      }
    )
    compiler.search.search(identifier, buildTargetIdentifier, visitor)
    result.result()
  }

}

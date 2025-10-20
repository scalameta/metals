package scala.meta.internal.jpc

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

import scala.meta.internal.metals.CompilerVirtualFileParams
import scala.meta.internal.mtags.CommonMtagsEnrichments._
import scala.meta.internal.pc.CompletionFuzzy
import scala.meta.pc.OffsetParams

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree.Kind._
import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath
import com.sun.source.util.Trees
import com.sun.tools.javac.code.Type.PackageType
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionItemKind
import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.InsertTextFormat
import org.eclipse.{lsp4j => l}

class JavaCompletionProvider(
    compiler: JavaMetalsCompiler,
    params: OffsetParams,
    isCompletionSnippetsEnabled: Boolean
) {
  private val includeDetailInLabel =
    compiler.metalsConfig.isDetailIncludedInLabel
  var scanner: JavaTreeScanner = _

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
    val compile =
      compiler
        .compilationTask(
          CompilerVirtualFileParams(
            params.uri(),
            textWithSemicolon,
            params.token()
          )
        )
        .withAnalyzePhase()
    val task = compile.task
    val cu = compile.cu
    scanner = new JavaTreeScanner(compiler.logger, task, cu)
    val position =
      CursorPosition(params.offset(), params.offset(), params.offset())
    val node = compiler.compilerTreeNode(scanner, position)

    node match {
      case Some(n) =>
        val items = n.getLeaf.getKind match {
          case MEMBER_SELECT =>
            completeMemberSelect(task, n).distinct
          case IDENTIFIER =>
            completeIdentifier(task, n).distinct ++ keywords(n)
          case NEW_CLASS =>
            completeIdentifier(
              task,
              n,
              elementFilter = _.getKind() == ElementKind.CONSTRUCTOR
            ).distinct
          case _ =>
            keywords(n)
        }
        new CompletionList(items.asJava)
      case None => new CompletionList()
    }
  }

  private def identifierScore(element: Element): Int = {
    val name = element.getSimpleName().toString().toLowerCase()
    name.indexOf(identifier) match {
      case 0 => 0
      case -1 => 2
      case _ => 1
    }
  }

  private val defaultMembers = Set(
    "clone", "finalize", "getClass", "hashCode", "equals", "toString", "notify",
    "notifyAll", "wait"
  )

  private def memberScore(element: Element, containingElement: Element): Int = {
    var score = 0
    identifierScore(element) match {
      case 0 => score = score | (1 << 28)
      case 1 => score = score | (1 << 27)
      case _ =>
    }
    if (element.getEnclosingElement() == containingElement) {
      score = score | (1 << 26)
    }
    if (!defaultMembers.contains(element.getSimpleName().toString())) {
      score = score | (1 << 25)
    }
    -score
  }

  private def completeMemberSelect(
      task: JavacTask,
      path: TreePath
  ): List[CompletionItem] = {
    val typeAnalyzer = new JavaTypeAnalyzer(task)
    val select = path.getLeaf.asInstanceOf[MemberSelectTree]
    val newPath = new TreePath(path, select.getExpression)
    val memberType = typeAnalyzer.typeMirror(newPath)

    memberType match {
      case dt: DeclaredType => completeDeclaredType(task, dt)
      case _: ArrayType => completeArrayType()
      case tv: TypeVariable => completeTypeVariable(task, tv)
      case pkg: PackageType => completePackageType(task, pkg)
      case _ => Nil
    }
  }

  private def completeIdentifier(
      task: JavacTask,
      path: TreePath,
      elementFilter: Element => Boolean = _ => true
  ): List[CompletionItem] =
    completeFromScope(task, path, elementFilter)

  private def completeFromScope(
      task: JavacTask,
      path: TreePath,
      elementFilter: Element => Boolean
  ): List[CompletionItem] = {
    val trees = Trees.instance(task)
    val scope = trees.getScope(path)

    val scopeCompletion = JavaScopeVisitor.scopeMembers(task, scope)
    val identifier = extractIdentifier
    val items = List.newBuilder[CompletionItem]

    items ++= scopeCompletion
      .sortBy(el => identifierScore(el))
      .iterator
      .filter(elementFilter)
      .map(completionItem)
      .filter(item => CompletionFuzzy.matches(identifier, item.getLabel))

    val inScope = scopeCompletion.map(_.toString()).toSet
    autoImportItems(
      identifier,
      (elem, item) => {
        if (!inScope.contains(elem)) {
          items += item
        }
      }
    )

    items.result()
  }

  private def autoImportItems(
      query: String,
      onMatch: (String, CompletionItem) => Unit
  ): Unit = {
    compiler.doSearch(query).foreach { fqn =>
      val parts = fqn.split('.')
      val simpleName = parts.last
      val packageName = parts.dropRight(1).mkString(".")
      val item = new l.CompletionItem()
      item.setKind(CompletionItemKind.Class)
      if (includeDetailInLabel) {
        item.setLabel(s"${simpleName} - ${packageName}")
      } else {
        item.setLabel(simpleName)
        item.setDetail(packageName)
      }
      val edit = new l.TextEdit(
        new l.Range(
          Positions.toLspPosition(
            scanner.root.getLineMap(),
            params.offset() - identifier.length(),
            params.text()
          ),
          Positions.toLspPosition(
            scanner.root.getLineMap(),
            params.offset(),
            params.text()
          )
        ),
        simpleName
      )
      item.setTextEdit(edit)
      item.setKind(CompletionItemKind.Class)
      // TODO?: move auto-import computation to completionItem/resolve so we
      // don't compute this eagerly for all items here.
      val additionalEdit =
        new JavaAutoImportEditor(params.text(), fqn).textEdit()
      item.setAdditionalTextEdits(List(additionalEdit).asJava)
      onMatch(fqn, item)
      item
    }
  }

  private def completePackageType(
      task: JavacTask,
      pkg: PackageType
  ): List[CompletionItem] = {
    val pkgElement = task.getElements().getPackageOf(pkg.asElement())
    val v = new JavaCompletionSearchVisitor()
    compiler.search.search(
      pkgElement.getQualifiedName().toString(),
      this.compiler.buildTargetId,
      v
    )
    val fuzzyMatches = List.newBuilder[CompletionItem]
    v.visitedFQN.foreach { fqn =>
      val elem = task.getElements().getTypeElement(fqn)
      if (elem != null) {
        val item = completionItem(elem)
        item.setKind(CompletionItemKind.Class)
        fuzzyMatches += item
      }
    }
    fuzzyMatches.result()
  }

  private def completeDeclaredType(
      task: JavacTask,
      declaredType: DeclaredType
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
      .filter(member =>
        CompletionFuzzy.matches(
          identifier,
          member.getSimpleName.toString
        ) && !bannedKinds(member.getKind())
      )
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

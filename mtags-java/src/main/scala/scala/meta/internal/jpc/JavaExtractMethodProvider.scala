package scala.meta.internal.jpc

import javax.lang.model.`type`.ArrayType
import javax.lang.model.`type`.DeclaredType
import javax.lang.model.`type`.TypeKind
import javax.lang.model.`type`.TypeMirror
import javax.lang.model.`type`.TypeVariable
import javax.lang.model.`type`.WildcardType
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.TypeElement
import javax.lang.model.element.TypeParameterElement
import javax.lang.model.element.VariableElement
import javax.lang.model.util.Types

import scala.collection.mutable
import scala.jdk.CollectionConverters._

import scala.meta.internal.pc.ExtractMethodUtils
import scala.meta.pc.DisplayableException
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

import com.sun.source.tree.ArrayAccessTree
import com.sun.source.tree.BinaryTree
import com.sun.source.tree.BlockTree
import com.sun.source.tree.BreakTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.ConditionalExpressionTree
import com.sun.source.tree.ContinueTree
import com.sun.source.tree.ExpressionStatementTree
import com.sun.source.tree.ExpressionTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.InstanceOfTree
import com.sun.source.tree.LiteralTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodInvocationTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.ParenthesizedTree
import com.sun.source.tree.ReturnTree
import com.sun.source.tree.Tree
import com.sun.source.tree.TypeCastTree
import com.sun.source.tree.UnaryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import org.eclipse.{lsp4j => l}

final class JavaExtractMethodProvider(
    compiler: JavaMetalsCompiler,
    rangeParams: RangeParams,
    extractionPos: OffsetParams
) extends ExtractMethodUtils {

  def extractMethod: List[l.TextEdit] = {
    rangeParams.checkCanceled()
    val compile = compiler.compilationTask(rangeParams).withAnalyzePhase()
    rangeParams.checkCanceled()

    val javacTrees = Trees.instance(compile.task)
    val cu = compile.cu
    val text = cu.getSourceFile().getCharContent(true).toString()
    val originalStart = rangeParams.offset()
    val originalEnd = rangeParams.endOffset()
    val insertOffset = extractionPos.offset()
    if (originalStart >= originalEnd || insertOffset < 0) Nil
    else {
      val ctx = Context(
        javacTrees,
        compile.task.getTypes(),
        cu,
        text,
        originalStart,
        originalEnd,
        originalStart,
        originalEnd
      )
      findSelection(ctx).fold(List.empty[l.TextEdit]) { selection =>
        generateEdits(selection, ctx, insertOffset)
      }
    }
  }

  private case class Context(
      trees: Trees,
      types: Types,
      cu: CompilationUnitTree,
      text: String,
      rangeStart: Int,
      rangeEnd: Int,
      originalStart: Int,
      originalEnd: Int
  ) {
    val packageName: Option[String] =
      Option(cu.getPackageName()).map(_.toString).filter(_.nonEmpty)
    val existingImports: Set[String] =
      ImportLine
        .fromText(text)
        .map(_.line.stripPrefix("import ").stripSuffix(";").trim)
        .toSet
    val importedSimpleNames: Map[String, String] =
      existingImports.flatMap { imp =>
        val simple = imp.split('.').last
        if (simple == "*") None else Some(simple -> imp)
      }.toMap

    def encloses(start: Int, end: Int): Boolean =
      rangeStart <= start && end <= rangeEnd

    def startOf(tree: Tree): Int =
      trees.getSourcePositions().getStartPosition(cu, tree).toInt

    def endOf(tree: Tree): Int =
      trees.getSourcePositions().getEndPosition(cu, tree).toInt

    def elementAt(path: TreePath): Option[Element] =
      Option(trees.getElement(path))

    def typeAt(path: TreePath): Option[TypeMirror] =
      Option(trees.getTypeMirror(path))
  }

  private case class Selection(
      methodPath: TreePath,
      classPath: TreePath,
      statements: List[Tree],
      exprPath: Option[TreePath],
      asStatement: Boolean
  )

  private def findSelection(ctx: Context): Option[Selection] = {
    val finder = new SelectionFinder(ctx)
    finder.scan(ctx.cu, ())
    finder.found.collect { case selection =>
      hasInvalid(selection).foreach { message =>
        throw new DisplayableException(message)
      }
      selection
    }
  }

  /**
   * IT might be tto tricky to get it right with return statements.
   */
  private def hasInvalid(selection: Selection): Option[String] = {
    var hasInvalid: Option[String] = None
    val scanner = new TreeScanner[Unit, Unit] {
      override def visitReturn(node: ReturnTree, p: Unit): Unit = {
        hasInvalid = Some(
          "Cannot extract selection that contains return statements"
        )
      }
      override def visitIdentifier(node: IdentifierTree, p: Unit): Unit = {
        if (node.getName().toString() == "super")
          hasInvalid = Some(
            "Cannot extract selection that contains super calls"
          )
        super.visitIdentifier(node, p)
      }
      override def visitBreak(node: BreakTree, p: Unit): Unit =
        hasInvalid = Some(
          "Cannot extract selection that contains break statements"
        )
      override def visitContinue(node: ContinueTree, p: Unit): Unit =
        hasInvalid = Some(
          "Cannot extract selection that contains continue statements"
        )
    }
    for (stmt <- selection.statements if hasInvalid.isEmpty) {
      scanner.scan(stmt, ())
    }
    hasInvalid
  }

  private final class SelectionFinder(ctx: Context)
      extends TreePathScanner[Unit, Unit] {
    private var result: Option[Selection] = None
    private var methodPath: Option[TreePath] = None
    private var classPath: Option[TreePath] = None
    private var bestExpr: Option[(TreePath, Int)] = None

    override def visitCompilationUnit(
        node: CompilationUnitTree,
        p: Unit
    ): Unit =
      scan(node.getTypeDecls(), p)

    override def visitClass(node: ClassTree, p: Unit): Unit = {
      if (overlaps(ctx.startOf(node), ctx.endOf(node))) {
        val previous = classPath
        classPath = Some(getCurrentPath())
        try super.visitClass(node, p)
        finally {
          if (previous.isDefined)
            classPath = previous
        }
      }
    }

    override def visitMethod(node: MethodTree, p: Unit): Unit = {
      if (overlaps(ctx.startOf(node), ctx.endOf(node))) {
        val previous = methodPath
        methodPath = Some(getCurrentPath())
        try super.visitMethod(node, p)
        finally {
          if (previous.isDefined)
            methodPath = previous
        }
      }
    }

    override def visitMemberSelect(node: MemberSelectTree, p: Unit): Unit = {
      super.visitMemberSelect(node, p)
    }
    override def visitExpressionStatement(
        node: ExpressionStatementTree,
        p: Unit
    ): Unit = {
      if (result.isEmpty && ctx.encloses(ctx.startOf(node), ctx.endOf(node))) {
        setExpression(node.getExpression(), asStatement = true)
      }
      super.visitExpressionStatement(node, p)
    }

    override def visitBlock(node: BlockTree, p: Unit): Unit = {
      if (result.isEmpty) {
        val selected = node
          .getStatements()
          .asScala
          .filter { stmt =>
            val (s, e) = (ctx.startOf(stmt), ctx.endOf(stmt))
            ctx.encloses(s, e)
          }
          .toList
        if (selected.size > 1) {
          for {
            method <- methodPath
            cls <- classPath
          } result = Some(
            Selection(method, cls, selected, None, asStatement = false)
          )
        }
      }
      super.visitBlock(node, p)
      if (result.isEmpty) {
        val selected = node
          .getStatements()
          .asScala
          .filter { stmt =>
            val (s, e) = (ctx.startOf(stmt), ctx.endOf(stmt))
            ctx.encloses(s, e) && !stmt.isInstanceOf[ExpressionStatementTree]
          }
          .toList
        if (selected.nonEmpty) {
          for {
            method <- methodPath
            cls <- classPath
          } result = Some(
            Selection(method, cls, selected, None, asStatement = false)
          )
        }
      }
    }

    override def scan(tree: Tree, p: Unit): Unit = {
      tree match {
        case expr: ExpressionTree if isExtractableExpression(expr) =>
          val (start, end) = (ctx.startOf(expr), ctx.endOf(expr))
          if (ctx.encloses(start, end)) {
            val span = end - start
            if (bestExpr.forall(_._2 < span)) {
              bestExpr = Some((new TreePath(getCurrentPath(), expr), span))
            }
          }
        case _ =>
      }
      super.scan(tree, p)
    }

    def found: Option[Selection] = {
      if (result.isEmpty) {
        for {
          (path, _) <- bestExpr
          method <- methodPath
          cls <- classPath
        } result = Some(
          Selection(
            method,
            cls,
            List(path.getLeaf()),
            Some(path),
            asStatement = false
          )
        )
      }
      result
    }

    private def setExpression(
        expr: ExpressionTree,
        asStatement: Boolean
    ): Unit = {
      for {
        method <- methodPath
        cls <- classPath
      } {
        val path = new TreePath(getCurrentPath(), expr)
        result = Some(
          Selection(method, cls, List(expr), Some(path), asStatement)
        )
      }
    }

    private def overlaps(start: Int, end: Int): Boolean =
      start <= ctx.rangeEnd && ctx.rangeStart <= end
  }

  private def generateEdits(
      selection: Selection,
      ctx: Context,
      insertOffset: Int
  ): List[l.TextEdit] = {
    val methodElement = ctx.elementAt(selection.methodPath).collect {
      case e: ExecutableElement => e
    }
    if (methodElement.isEmpty) Nil
    else generateEdits(selection, ctx, insertOffset, methodElement.get)

  }

  private def generateEdits(
      selection: Selection,
      ctx: Context,
      insertOffset: Int,
      methodElement: ExecutableElement
  ): List[l.TextEdit] = {
    val classTree = selection.classPath.getLeaf().asInstanceOf[ClassTree]
    val typePrinter = new TypePrinter(ctx)
    val freeVariables = collectFreeVariables(ctx, methodElement)
    val usedNames = classTree
      .getMembers()
      .asScala
      .collect { case m: MethodTree =>
        m.getName().toString()
      }
      .toSet
    val methodName = genName(usedNames, "newMethod")
    val paramsText = freeVariables
      .map(v => s"${typePrinter.print(v.asType())} ${v.getSimpleName()}")
      .mkString(", ")
    val argsText =
      freeVariables.map(_.getSimpleName().toString()).mkString(", ")

    val lineStart = lineStartAt(ctx.text, insertOffset)
    val indent = lineIndent(ctx.text, lineStart)
    val bodyIndent = indent + indentUnit(ctx.text, lineStart)

    val extractStart = ctx.startOf(selection.statements.head)
    val extractEnd = ctx.endOf(selection.statements.last)
    val (returnType, callText, methodBody) =
      selection.exprPath match {
        case Some(path) =>
          val tpe =
            ctx.typeAt(path).getOrElse(ctx.types.getNoType(TypeKind.VOID))
          val isVoid = tpe.getKind == TypeKind.VOID
          val call =
            if (selection.asStatement || isVoid) s"$methodName($argsText)"
            else s"$methodName($argsText)"
          val exprText = ctx.text.slice(extractStart, extractEnd).trim
          val body =
            if (isVoid) s"$bodyIndent$exprText"
            else {
              val stripped =
                if (selection.asStatement) exprText.stripSuffix(";")
                else exprText
              s"${bodyIndent}return $stripped;"
            }
          (tpe, call, body)
        case None =>
          val method = selection.methodPath.getLeaf().asInstanceOf[MethodTree]
          inferStatementReturn(selection, ctx, method) match {
            case Some((tpe, outputName)) =>
              val call =
                if (tpe.getKind == TypeKind.VOID) s"$methodName($argsText);"
                else
                  s"${typePrinter.print(tpe)} ${outputName} = $methodName($argsText);"
              val body =
                if (tpe.getKind == TypeKind.VOID)
                  statementBody(
                    ctx,
                    insertOffset,
                    extractStart,
                    extractEnd
                  )
                else
                  s"${statementBody(ctx, insertOffset, extractStart, extractEnd)}\n${bodyIndent}return $outputName;"
              (tpe, call, body)
            case None =>
              throw new DisplayableException(
                "No return type can be inferred, multiple variables are used after the selection."
              )
          }
      }

    val returnTypeText = typePrinter.print(returnType)
    val signatureReturn =
      if (returnType.getKind == TypeKind.VOID) "void" else returnTypeText
    val isStatic = methodElement.getModifiers().contains(Modifier.STATIC)
    val staticModifier = if (isStatic) "static " else ""

    val usedTypeVars = collectTypeVariables(returnType) ++
      freeVariables.flatMap(v => collectTypeVariables(v.asType()))
    val methodTypeParams = methodElement.getTypeParameters().asScala.toList
    val usedTypeVarNames =
      usedTypeVars.map(_.asElement().getSimpleName().toString())
    val requiredTypeParams = methodTypeParams.filter { tp =>
      usedTypeVarNames.contains(tp.getSimpleName().toString())
    }
    val typeParamClause =
      if (requiredTypeParams.isEmpty) ""
      else {
        val printed =
          requiredTypeParams.map(tp => printTypeParameter(tp, typePrinter))
        s"<${printed.mkString(", ")}> "
      }

    val methodText =
      s"${indent}private ${staticModifier}${typeParamClause}$signatureReturn $methodName($paramsText) {\n$methodBody\n$indent}\n\n"

    val importEdits = typePrinter.importEditsFor(
      returnType +: freeVariables.map(_.asType())
    )

    importEdits :+
      new l.TextEdit(
        new l.Range(
          Positions.toLspPosition(ctx.cu.getLineMap(), lineStart, ctx.text),
          Positions.toLspPosition(ctx.cu.getLineMap(), lineStart, ctx.text)
        ),
        methodText
      ) :+
      new l.TextEdit(
        Positions.toLspRange(
          ctx.cu.getLineMap(),
          extractStart,
          extractEnd,
          ctx.text
        ),
        callText
      )
  }

  private def statementBody(
      ctx: Context,
      insertOffset: Int,
      extractStart: Int,
      extractEnd: Int
  ): String = {
    val bodyIndent = lineIndent(ctx.text, lineStartAt(ctx.text, insertOffset)) +
      indentUnit(ctx.text, lineStartAt(ctx.text, insertOffset))
    reindent(ctx.text.slice(extractStart, extractEnd), bodyIndent)
  }

  private def reindent(text: String, bodyIndent: String): String = {
    val minIndent = text.linesIterator
      .map(_.takeWhile(c => c == ' ' || c == '\t').length)
      .filter(_ > 0)
      .minOption
      .getOrElse(0)
    text.linesIterator
      .map { line =>
        val trimmed = line.trim
        if (trimmed.isEmpty) ""
        else {
          val leading = line.takeWhile(c => c == ' ' || c == '\t')
          val dedented =
            if (leading.length >= minIndent) line.drop(minIndent).trim
            else trimmed
          s"$bodyIndent$dedented"
        }
      }
      .filter(_.nonEmpty)
      .mkString("\n")
  }

  private def inferStatementReturn(
      selection: Selection,
      ctx: Context,
      method: MethodTree
  ): Option[(TypeMirror, String)] = {
    val declared = mutable.Map.empty[String, VariableElement]
    val declScanner = new TreePathScanner[Unit, Unit] {
      override def visitVariable(node: VariableTree, p: Unit): Unit = {
        val (start, end) = (ctx.startOf(node), ctx.endOf(node))
        if (ctx.encloses(start, end)) {
          ctx.elementAt(getCurrentPath()).foreach {
            case v: VariableElement if isLocalLike(v) =>
              declared(v.getSimpleName().toString()) = v
            case _ =>
          }
        }
        super.visitVariable(node, p)
      }
    }
    declScanner.scan(ctx.cu, ())

    val selectionEnd = ctx.endOf(selection.statements.last)
    val usedAfter = mutable.Set.empty[String]
    Option(method.getBody()).foreach { block =>
      block.getStatements().asScala.foreach { stmt =>
        if (ctx.startOf(stmt) >= selectionEnd) {
          usedAfter ++= identifierNames(stmt)
        }
      }
    }

    declared.keys.filter(usedAfter.contains).toList match {
      case Nil => Some((ctx.types.getNoType(TypeKind.VOID), ""))
      case name :: Nil => declared.get(name).map(v => (v.asType(), name))
      case _ => None
    }
  }

  private def collectFreeVariables(
      ctx: Context,
      methodElement: ExecutableElement
  ): List[VariableElement] = {
    val params = mutable.LinkedHashMap.empty[String, VariableElement]
    val scanner = new TreePathScanner[Unit, Unit] {
      override def visitIdentifier(node: IdentifierTree, p: Unit): Unit = {
        val (start, end) = (ctx.startOf(node), ctx.endOf(node))
        if (ctx.rangeStart <= start && end <= ctx.rangeEnd) {
          ctx.elementAt(getCurrentPath()).foreach {
            case v: VariableElement if isFreeVariable(v, ctx, methodElement) =>
              params.getOrElseUpdate(v.getSimpleName().toString(), v)
            case _ =>
          }
        }
        super.visitIdentifier(node, p)
      }
    }
    scanner.scan(ctx.cu, ())
    params.values.toList
  }

  private def isFreeVariable(
      variable: VariableElement,
      ctx: Context,
      methodElement: ExecutableElement
  ): Boolean =
    if (!isLocalLike(variable) && variable.getKind != ElementKind.PARAMETER)
      false
    else if (!isEnclosedBy(variable, methodElement)) false
    else
      declarationTree(variable, ctx).forall { tree =>
        !ctx.encloses(ctx.startOf(tree), ctx.endOf(tree))
      }

  private def declarationTree(
      variable: VariableElement,
      ctx: Context
  ): Option[Tree] = {
    object finder extends TreePathScanner[Unit, Unit] {
      var found: Option[Tree] = None
      override def visitVariable(node: VariableTree, p: Unit): Unit = {
        ctx.elementAt(getCurrentPath()).foreach { elem =>
          if (elem == variable) found = Some(node)
        }
        super.visitVariable(node, p)
      }
    }
    finder.scan(ctx.cu, ())
    finder.found
  }

  private def identifierNames(tree: Tree): Set[String] = {
    val names = mutable.Set.empty[String]
    tree.accept(
      new TreeScanner[Unit, Unit] {
        override def visitIdentifier(node: IdentifierTree, p: Unit): Unit = {
          names += node.getName().toString()
          super.visitIdentifier(node, p)
        }
      },
      ()
    )
    names.toSet
  }

  private def isLocalLike(element: VariableElement): Boolean =
    Set(
      ElementKind.LOCAL_VARIABLE,
      ElementKind.PARAMETER,
      ElementKind.EXCEPTION_PARAMETER,
      ElementKind.RESOURCE_VARIABLE,
      ElementKind.BINDING_VARIABLE
    ).contains(element.getKind)

  private def isEnclosedBy(element: Element, enclosing: Element): Boolean =
    Iterator
      .iterate(Option(element))(_.flatMap(e => Option(e.getEnclosingElement())))
      .takeWhile(_.nonEmpty)
      .flatten
      .exists(_ == enclosing)

  private def isExtractableExpression(tree: ExpressionTree): Boolean =
    tree match {
      case _: IdentifierTree => true
      case _: LiteralTree => true
      case _: MethodInvocationTree => true
      case _: MemberSelectTree => true
      case _: BinaryTree => true
      case _: UnaryTree => true
      case _: ConditionalExpressionTree => true
      case _: NewClassTree => true
      case _: ArrayAccessTree => true
      case _: TypeCastTree => true
      case _: InstanceOfTree => true
      case _: ParenthesizedTree => true
      case _ => false
    }

  private def lineStartAt(text: String, offset: Int): Int =
    text.lastIndexOf('\n', math.max(0, offset - 1)) + 1

  private def lineIndent(text: String, lineStart: Int): String =
    text.slice(lineStart, text.length()).takeWhile(c => c == ' ' || c == '\t')

  private def indentUnit(text: String, lineStart: Int): String =
    if (lineIndent(text, lineStart).contains('\t')) "\t" else "  "

  private def collectTypeVariables(tpe: TypeMirror): Set[TypeVariable] =
    tpe.getKind match {
      case TypeKind.TYPEVAR =>
        Set(tpe.asInstanceOf[TypeVariable])
      case TypeKind.DECLARED =>
        tpe
          .asInstanceOf[DeclaredType]
          .getTypeArguments()
          .asScala
          .flatMap(collectTypeVariables)
          .toSet
      case TypeKind.ARRAY =>
        collectTypeVariables(tpe.asInstanceOf[ArrayType].getComponentType)
      case TypeKind.WILDCARD =>
        val wildcard = tpe.asInstanceOf[WildcardType]
        Option(wildcard.getExtendsBound())
          .map(collectTypeVariables)
          .getOrElse(Set.empty) ++
          Option(wildcard.getSuperBound())
            .map(collectTypeVariables)
            .getOrElse(Set.empty)
      case _ => Set.empty
    }

  private def printTypeParameter(
      tp: TypeParameterElement,
      typePrinter: TypePrinter
  ): String = {
    val name = tp.getSimpleName().toString()
    val bounds = tp.getBounds().asScala.toList
    bounds match {
      case Nil => name
      case head :: Nil if head.toString == "java.lang.Object" => name
      case _ =>
        val boundStrs = bounds.map(typePrinter.print)
        s"$name extends ${boundStrs.mkString(" & ")}"
    }
  }

  private final class TypePrinter(ctx: Context) {
    private val importsNeeded = mutable.LinkedHashSet.empty[String]
    private val forcedFqn = mutable.Set.empty[String]

    def print(tpe: TypeMirror): String = {
      collectImportCandidates(tpe)
      printType(tpe)
    }

    def importEditsFor(types: Seq[TypeMirror]): List[l.TextEdit] = {
      types.foreach(collectImportCandidates)
      if (importsNeeded.isEmpty) Nil
      else {
        val lines = importsNeeded.toList.sorted.map(fqn => s"import $fqn;")
        val firstFqn = lines.head.stripPrefix("import ").stripSuffix(";")
        val anchor = new JavaAutoImportEditor(ctx.text, firstFqn).textEdit()
        if (lines.size == 1) List(anchor)
        else
          List(
            new l.TextEdit(
              anchor.getRange(),
              anchor.getNewText() + lines.tail.mkString("\n", "\n", "\n")
            )
          )
      }
    }

    private def collectImportCandidates(tpe: TypeMirror): Unit =
      tpe.getKind match {
        case TypeKind.DECLARED =>
          tpe match {
            case declared: DeclaredType =>
              Option(declared.asElement()).collect { case t: TypeElement =>
                collectTypeElement(t)
              }
              declared
                .getTypeArguments()
                .asScala
            case _ =>
          }

        case TypeKind.ARRAY =>
          tpe match {
            case array: ArrayType =>
              collectImportCandidates(array.getComponentType)
            case _ =>
          }
        case TypeKind.TYPEVAR =>
          tpe match {
            case typevar: TypeVariable =>
              collectImportCandidates(typevar.getUpperBound)
            case _ =>
          }
        case TypeKind.WILDCARD =>
          tpe match {
            case wildcard: WildcardType =>
              Option(wildcard.getExtendsBound())
                .foreach(collectImportCandidates)
              Option(wildcard.getSuperBound()).foreach(collectImportCandidates)
            case _ =>
          }
        case _ =>
      }

    private def collectTypeElement(elem: TypeElement): Unit = {
      val qn = elem.getQualifiedName().toString()
      if (needsImport(qn, elem.getSimpleName().toString())) importsNeeded += qn
    }

    private def needsImport(fqn: String, simple: String): Boolean =
      if (fqn.startsWith("java.lang.")) false
      else if (ctx.packageName.contains(fqn.split('.').init.mkString(".")))
        false
      else if (ctx.existingImports.contains(fqn)) false
      else
        ctx.importedSimpleNames.get(simple) match {
          case Some(existing) if existing != fqn =>
            forcedFqn += simple
            false
          case Some(_) => false
          case None => true
        }

    private def printType(tpe: TypeMirror): String =
      tpe.getKind match {
        case TypeKind.VOID => "void"
        case TypeKind.BOOLEAN => "boolean"
        case TypeKind.BYTE => "byte"
        case TypeKind.SHORT => "short"
        case TypeKind.INT => "int"
        case TypeKind.LONG => "long"
        case TypeKind.CHAR => "char"
        case TypeKind.FLOAT => "float"
        case TypeKind.DOUBLE => "double"
        case TypeKind.NULL => "null"
        case TypeKind.DECLARED =>
          val declared = tpe.asInstanceOf[DeclaredType]
          val elem = declared.asElement().asInstanceOf[TypeElement]
          val qn = elem.getQualifiedName().toString()
          val simple = elem.getSimpleName().toString()
          val base =
            if (forcedFqn.contains(simple) || shouldUseFqn(qn, simple)) qn
            else simple
          val args = declared.getTypeArguments().asScala
          if (args.isEmpty) base
          else s"$base<${args.map(printType).mkString(", ")}>"
        case TypeKind.ARRAY =>
          s"${printType(tpe.asInstanceOf[ArrayType].getComponentType)}[]"
        case TypeKind.TYPEVAR =>
          tpe.asInstanceOf[TypeVariable].asElement().getSimpleName().toString()
        case TypeKind.WILDCARD =>
          val wildcard = tpe.asInstanceOf[WildcardType]
          Option(wildcard.getExtendsBound()) match {
            case Some(bound) => s"? extends ${printType(bound)}"
            case None =>
              Option(wildcard.getSuperBound()) match {
                case Some(bound) => s"? super ${printType(bound)}"
                case None => "?"
              }
          }
        case _ => tpe.toString
      }

    private def shouldUseFqn(fqn: String, simple: String): Boolean =
      forcedFqn.contains(simple) ||
        (!fqn.startsWith("java.lang.") &&
          !ctx.packageName.contains(fqn.split('.').init.mkString(".")) &&
          !ctx.existingImports.contains(fqn) &&
          !importsNeeded.contains(fqn))
  }
}

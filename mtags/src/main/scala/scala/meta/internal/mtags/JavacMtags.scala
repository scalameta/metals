package scala.meta.internal.mtags

import java.io.PrintWriter
import java.io.StringWriter
import java.net.URI
import java.nio.charset.StandardCharsets
import java.{util => ju}
import javax.lang.model.element.Modifier
import javax.tools.DiagnosticCollector
import javax.tools.DiagnosticListener
import javax.tools.JavaFileManager
import javax.tools.JavaFileObject
import javax.tools.StandardLocation

import scala.collection.JavaConverters._
import scala.collection.mutable.Buffer
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.metals.CompilerRangeParamsUtils
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.internal.metals.PositionSyntax.XtensionPositionsScalafix
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.ReportContext
import scala.meta.internal.mtags.JavacMtags.Direction
import scala.meta.internal.mtags.JavacMtags.FromEnd
import scala.meta.internal.mtags.JavacMtags.FromStart
import scala.meta.internal.mtags.JavacMtags.ParseTask
import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.{semanticdb => s}

import com.sun.source.tree.AnnotationTree
import com.sun.source.tree.BlockTree
import com.sun.source.tree.CatchTree
import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.EnhancedForLoopTree
import com.sun.source.tree.ForLoopTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.LambdaExpressionTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.MemberReferenceTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.PackageTree
import com.sun.source.tree.Tree
import com.sun.source.tree.Tree.Kind.INTERFACE
import com.sun.source.tree.TryTree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import com.sun.tools.javac.api.JavacTrees
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.parser.ParserFactory
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import com.sun.tools.javac.util.Options
import org.eclipse.{lsp4j => l}
import org.slf4j.LoggerFactory

object JavacMtags {
  case class ParseTask(
      factory: ParserFactory,
      fileManager: JavaFileManager,
      diagnostics: DiagnosticCollector[JavaFileObject],
      context: Context
  )
  private sealed abstract class Direction
  private final case class FromEnd(node: Tree) extends Direction
  private final case class FromStart(node: Tree) extends Direction

  // directly construct a parser factory instead of going via javac getTask()
  // since it has a lot of overhead from scanning the classpath.
  def createParserFactory(
      ctx: Context
  ): ParseTask = {
    val silentWriter = new PrintWriter(new StringWriter())
    Log.preRegister(ctx, silentWriter)
    ctx.put(Log.outKey, silentWriter)
    ctx.put(Log.errKey, silentWriter)
    val diagnostics = new DiagnosticCollector[JavaFileObject]()
    ctx.put(classOf[DiagnosticListener[_]], diagnostics)
    val fileManager = new JavacFileManager(ctx, false, StandardCharsets.UTF_8)
    Options.instance(ctx).put("--enable-preview", "true");
    Options.instance(ctx).put("allowStringFolding", "false");
    fileManager.setLocation(
      StandardLocation.PLATFORM_CLASS_PATH,
      ju.Collections.emptyList()
    );
    fileManager.setContext(ctx)
    ctx.put(classOf[JavaFileManager], fileManager)
    ParseTask(ParserFactory.instance(ctx), fileManager, diagnostics, ctx)
  }
}
class JavacMtags(
    val input: Input.VirtualFile,
    includeMembers: Boolean = false,
    includeFuzzyReferences: Boolean = false,
    includeUniqueFuzzyReferences: Boolean =
      true // `true` only exists for testing purposes,
)(implicit rc: ReportContext)
    extends MtagsIndexer { mtags =>
  private val _names = new ju.LinkedHashSet[String]()
  private var parseTask = Option.empty[ParseTask]
  private val logger = LoggerFactory.getLogger(getClass)

  def language: Language = Language.JAVA
  def identifiers(): Seq[String] =
    _names.asScala.iterator.toIndexedSeq

  override def indexRoot(): Unit = try {
    val context = new Context()
    val task = JavacMtags.createParserFactory(context)
    this.parseTask = Some(task)
    val source = SourceJavaFileObject.make(input.text, URI.create(input.path))
    Log.instance(context).useSource(source)
    val parser = task.factory.newParser(
      input.text,
      false, // keepDocComments
      true, // keepEndPos
      true // keepLineMap
    )
    val cu = parser.parseCompilationUnit()
    cu.sourcefile = source
    val trees = JavacTrees.instance(context)
    val visitor = new Visitor(cu, trees)
    visitor.scan(cu, ())
    task.fileManager.close()
  } catch {
    case NonFatal(e) =>
      logger.error(s"Error processing file ${input.path}", e)
      reportError(e, None)
  }

  private val emptyScope = new SyntacticScope(null)
  private class SyntacticScope(outer: SyntacticScope) {
    private val names = new ju.LinkedHashSet[CharSequence]()
    def addName(name: CharSequence): Unit = {
      names.add(name)
    }
    def contains(name: CharSequence): Boolean = {
      if (names.contains(name)) {
        return true
      }
      if (outer == null) {
        return false
      }
      outer.contains(name)
    }
  }

  private class Visitor(cu: CompilationUnitTree, trees: Trees)
      extends TreePathScanner[TreePath, Unit] {
    private val sourcePositions = trees.getSourcePositions()
    private val lineMap: LineMap = cu.getLineMap()
    private var localVariableScope: SyntacticScope = emptyScope
    private var insidePackage: Boolean = false
    private var insideAnnotationArguments: Boolean = false
    private var insideExtendsClause: Boolean = false
    private def identifierSuffix: String = {
      if (insideExtendsClause && insidePackage) {
        throw new IllegalStateException("Inside extends clause and package")
      }

      if (insidePackage) "/"
      else if (insideExtendsClause) ":"
      // Named arguments like `@Deprecated(since = "1.0")` reference methods
      // `since()`, not fields.
      else if (insideAnnotationArguments) "()."
      else "."
    }

    private def addName(
        name: CharSequence,
        descriptorSuffix: String,
        position: Long
    ): Unit = {
      if (!includeFuzzyReferences) {
        return
      }
      if (localVariableScope.contains(name)) {
        return
      }
      if (name.length() == 0 || name.charAt(0) == '<') {
        return
      }
      if (
        CharSequence.compare(name, "this") == 0 ||
        CharSequence.compare(name, "super") == 0
      ) {
        return
      }
      val sym = s"$name$descriptorSuffix"
      if (_names.add(sym) || !includeUniqueFuzzyReferences) {
        val line = lineMap.getLineNumber(position).toInt - 1
        val character = lineMap.getColumnNumber(position).toInt - 1
        visitFuzzyReferenceOccurrence(
          s.SymbolOccurrence(
            symbol = sym,
            role = s.SymbolOccurrence.Role.REFERENCE,
            range = Some(
              correctForTabs(
                s.Range(
                  startLine = line,
                  startCharacter = character,
                  endLine = line,
                  endCharacter = character + name.length
                ),
                position
              )
            )
          )
        )
      }
    }

    private def correctForTabs(
        range: s.Range,
        start: Long
    ): s.Range = {
      var startLinePos =
        lineMap.getPosition(lineMap.getLineNumber(start), 0).toInt
      if (startLinePos < 0) {
        return range
      }

      // javac replaces every tab with 8 spaces in the linemap. As this is potentially
      // inconsistent
      // with the source file itself, we adjust for that here if the line is actually
      // indented with
      // tabs.
      // As for every tab there are 8 spaces, we remove 7 spaces for every tab to get
      // the correct
      // char offset (note: different to _column_ offset your editor shows)
      if (input.text.charAt(startLinePos) == '\t') {
        var count = 1;
        startLinePos += 1;
        while (input.text.charAt(startLinePos) == '\t') {
          count += 1
          startLinePos += 1
        }
        s.Range(
          startLine = range.startLine,
          startCharacter = range.startCharacter - (count * 7),
          endLine = range.endLine,
          endCharacter = range.endCharacter - (count * 7)
        )
      } else {
        range
      }
    }

    override def visitLambdaExpression(
        node: LambdaExpressionTree,
        p: Unit
    ): TreePath = {
      withScope {
        node.getParameters().forEach { parameter =>
          localVariableScope.addName(parameter.getName())
        }
        super.visitLambdaExpression(node, p)
      }
    }

    override def visitEnhancedForLoop(
        node: EnhancedForLoopTree,
        p: Unit
    ): TreePath = {
      withScope {
        localVariableScope.addName(node.getVariable().getName())
        super.visitEnhancedForLoop(node, p)
      }
    }

    override def visitForLoop(node: ForLoopTree, p: Unit): TreePath = {
      withScope {
        node.getInitializer().forEach {
          case variable: VariableTree =>
            localVariableScope.addName(variable.getName())
          case _ =>
        }
        super.visitForLoop(node, p)
      }
    }

    override def visitCatch(node: CatchTree, p: Unit): TreePath = {
      withScope {
        localVariableScope.addName(node.getParameter().getName())
        super.visitCatch(node, p)
      }
    }

    override def visitTry(node: TryTree, p: Unit): TreePath = {
      withScope {
        node.getResources().forEach {
          case resource: VariableTree =>
            localVariableScope.addName(resource.getName())
          case _ =>
        }
        super.visitTry(node, p)
      }
    }

    override def visitBlock(node: BlockTree, p: Unit): TreePath = {
      withScope {
        node.getStatements().forEach { statement =>
          statement match {
            case variable: VariableTree =>
              localVariableScope.addName(variable.getName())
            case _ =>
          }
        }
        super.visitBlock(node, p)
      }
    }

    private def findNameRange(
        start: Direction,
        end: Direction,
        name: String
    ): Position = {
      val startOffset = start match {
        case FromEnd(node) =>
          sourcePositions.getEndPosition(cu, node).intValue()
        case FromStart(node) =>
          sourcePositions.getStartPosition(cu, node).intValue()
      }
      val endOffset = end match {
        case FromEnd(node) =>
          sourcePositions.getEndPosition(cu, node).intValue()
        case FromStart(node) =>
          sourcePositions.getStartPosition(cu, node).intValue()
      }
      findNameRange(startOffset, endOffset, name)
    }

    private def findNameRange(
        start: Int,
        requestedEnd: Int,
        name: String
    ): Position = {
      val end = Math.max(start, requestedEnd)
      var i = start
      if (name.isEmpty() || start < 0) {
        return Position.None
      }
      while (i < end && i < mtags.input.text.length()) {
        val ch = mtags.input.text.charAt(i)
        if (Character.isJavaIdentifierStart(ch)) {
          var isMatch = ch == name.charAt(0)
          var j = 1
          while (
            j + i < end &&
            Character.isJavaIdentifierPart(mtags.input.text.charAt(j + i))
          ) {
            isMatch = isMatch &&
              j < name.length() &&
              mtags.input.text.charAt(j + i) == name.charAt(j)
            j += 1
          }
          if (isMatch) {
            val pos = Position.Range(mtags.input, i, i + name.length)
            return pos
          }
        }
        i += 1
      }
      Position.None
    }

    private val disambiguators = new ju.HashMap[MethodTree, Int]()
    private def updateDisambiguators(node: ClassTree): Unit = {
      // Per the SemanticDB spec, the disambiguator is the definition order of
      // the symbol where static methods are sorted to the end. This quirk is
      // because the Scala compiler doesn't preserve the original order of
      // methods in the Java class, it groups static methods into the "module
      // symbol" (companion object) even if Java doesn't have companion objects.
      val sorted = node
        .getMembers()
        .asScala
        .collect { case m: MethodTree => m }
        .sortBy(m => m.getModifiers().getFlags().contains(Modifier.STATIC))
      sorted.sliding(2).foreach {
        case Buffer(method1, method2) =>
          if (method1.getName().toString() != method2.getName().toString()) {
            disambiguators.put(method2, 0)
          } else {
            disambiguators.put(
              method2,
              disambiguators.getOrDefault(method1, 0) + 1
            )
          }
        case _ =>
      }

    }

    private val recordMembers = new ju.LinkedHashSet[VariableTree]()
    private val enumMembers = new ju.LinkedHashMap[VariableTree, NewClassTree]()
    override def visitClass(node: ClassTree, p: Unit): TreePath = {
      val name = node.getSimpleName().toString()
      if (isErrorName(name)) {
        return null
      }
      if (node.getKind() == Tree.Kind.RECORD) {
        node.getMembers().asScala.foreach {
          case v: VariableTree =>
            // Records are not allowed to have manually declaredinstance fields
            recordMembers.add(v)
          case _ =>
        }
      }
      if (node.getKind() == Tree.Kind.ENUM) {
        node.getMembers().asScala.foreach {
          case v: VariableTree =>
            Option(v.getInitializer()) match {
              case Some(n: NewClassTree)
                  if n.getIdentifier().toString() == name =>
                // Can't instantiate enums directly, this is how we
                // differentiate enum members from other members.
                enumMembers.put(v, n)
              case _ =>
            }
          case _ =>
        }
      }
      updateDisambiguators(node)
      mtags.withOwner() {
        val pos = findNameRange(
          start = node
            .getModifiers()
            .getAnnotations()
            .asScala
            .lastOption match {
            case None => FromStart(node)
            case Some(value) => FromEnd(value)
          },
          end = Option(node.getExtendsClause())
            .orElse(node.getImplementsClause().asScala.headOption)
            .orElse(node.getMembers().asScala.headOption)
            .map(FromStart(_))
            .getOrElse(FromEnd(node)),
          name = name
        )
        mtags.tpe(
          name,
          pos,
          kind = node.getKind() match {
            case INTERFACE => s.SymbolInformation.Kind.INTERFACE
            case _ => s.SymbolInformation.Kind.CLASS
          },
          if (node.getKind() == Tree.Kind.ENUM)
            s.SymbolInformation.Property.ENUM.value
          else 0
        )
        lazy val constructorCount = node.getMembers().asScala.count {
          case method: MethodTree =>
            val name = method.getName()
            name.length() > 0 && name.charAt(0) == '<'
          case _ => false
        }
        val isImplicitConstructor =
          node.getKind() == Tree.Kind.RECORD ||
            (node.getKind() == Tree.Kind.CLASS && constructorCount == 0)
        if (isImplicitConstructor) {
          mtags.withOwner() {
            mtags.ctor(
              if (constructorCount == 0) "()" else s"(+$constructorCount)",
              pos,
              0
            )
          }
        }
        var r = scan(node.getModifiers(), p);
        r = scanAndReduceIterable(node.getTypeParameters(), p, r);
        insideExtendsClause = true;
        r = scanAndReduce(node.getExtendsClause(), p, r);
        r = scanAndReduceIterable(node.getImplementsClause(), p, r);
        insideExtendsClause = false;
        r = scanAndReduceIterable(node.getMembers(), p, r);
        r;
      }
    }

    override def visitMethod(node: MethodTree, p: Unit): TreePath = {
      if (!includeMembers) {
        return null
      }
      val name = node.getName().toString()
      if (isErrorName(name)) {
        return null
      }
      mtags.withOwner() {
        mtags.method(
          name = name,
          disambiguator = disambiguators.getOrDefault(node, 0) match {
            case 0 => "()"
            case n => s"(+$n)"
          },
          pos = findNameRange(
            start = Option(node.getReturnType())
              .orElse(node.getModifiers().getAnnotations().asScala.lastOption)
              .map(FromEnd(_))
              .getOrElse(FromStart(node)),
            end = node
              .getParameters()
              .asScala
              .headOption
              .orElse(
                node.getThrows().asScala.headOption
              )
              .orElse(Option(node.getBody())) match {
              case None => FromEnd(node)
              case Some(value) => FromStart(value)
            },
            name = name match {
              case "<init>" => Symbol(owner).displayName
              case n => n
            }
          ),
          0,
          kind =
            if (name == "<init>") s.SymbolInformation.Kind.CONSTRUCTOR
            else s.SymbolInformation.Kind.METHOD
        )
        withScope {
          node.getParameters().forEach { parameter =>
            localVariableScope.addName(parameter.getName())
          }
          super.visitMethod(node, p)
        }
      }
    }

    override def visitVariable(node: VariableTree, p: Unit): TreePath = {
      if (!includeMembers) {
        return null
      }
      // addName(node.getName(), ".", sourcePositions.getStartPosition(cu, node))
      val name = node.getName().toString()
      if (isErrorName(name)) {
        return null
      }
      val enumMemberInitializer = enumMembers.remove(node)
      def isEnumMember = enumMemberInitializer != null

      mtags.withOwner() {
        val isLocal =
          this.localVariableScope.contains(node.getName()) ||
            // All nested variables inside methods are locals This should be
            // covered by `localVariableScope` but we don't handle 100% of cases
            // yet (example: instanceof binding `p instanceof Point p2`).
            mtags.currentOwner.endsWith(").")
        if (!isLocal) {
          val pos = findNameRange(
            start = Option(node.getType()) match {
              case Some(value)
                  if sourcePositions.getEndPosition(cu, value) >= 0 =>
                FromEnd(value)
              case _ => FromStart(node)
            },
            end = Option(node.getInitializer()) match {
              case None => FromEnd(node)
              case Some(value) => FromStart(value)
            },
            name = name
          )
          val isRecordMember = recordMembers.contains(node)
          if (isRecordMember) {
            mtags.method(
              name = name,
              disambiguator = "()",
              pos = pos,
              properties = 0
            )
          } else {
            mtags.term(
              name = name,
              pos = pos,
              kind = s.SymbolInformation.Kind.FIELD,
              properties =
                if (isEnumMember) s.SymbolInformation.Property.ENUM.value
                else 0
            )
          }
        }
      }
      if (enumMemberInitializer != null) {
        // The commented out lines are intentional, we skip visiting the type
        // declaration the name in the `new Name` expression.
        // Step 1: visit rest of VariableTree
        var r = scan(node.getModifiers(), p);
        // r = scanAndReduce(node.getType(), p, r);
        // r = scanAndReduce(node.getNameExpression(), p, r);
        // r = scanAndReduce(node.getInitializer(), p, r);

        // Step 2: visit rest of NewClassTree
        val init = enumMemberInitializer
        r = scan(init.getEnclosingExpression(), p);
        // r = scanAndReduce(init.getIdentifier(), p, r);
        // r = scanAndReduceIterable(init.getTypeArguments(), p, r);
        r = scanAndReduceIterable(init.getArguments(), p, r);
        // r = scanAndReduce(init.getClassBody(), p, r);
        return r;
        r;
      } else {
        super.visitVariable(node, p)
      }
    }
    override def visitAnnotation(node: AnnotationTree, p: Unit): TreePath = {
      var r = scan(node.getAnnotationType(), p);
      insideAnnotationArguments = true
      r = scanAndReduceIterable(node.getArguments(), p, r);
      insideAnnotationArguments = false
      r;
    }
    override def visitMemberSelect(
        node: MemberSelectTree,
        p: Unit
    ): TreePath = {
      val result = super.visitMemberSelect(node, p)
      addName(
        node.getIdentifier(),
        identifierSuffix,
        sourcePositions.getStartPosition(cu, node)
      )
      result
    }
    override def visitMemberReference(
        node: MemberReferenceTree,
        p: Unit
    ): TreePath = {
      val result = super.visitMemberReference(node, p)
      addName(node.getName(), "().", sourcePositions.getStartPosition(cu, node))
      result
    }
    override def visitPackage(node: PackageTree, p: Unit): TreePath = {
      node.getPackageName().toString().split("\\.").foreach { pkgName =>
        mtags.pkg(
          pkgName,
          findNameRange(
            start = FromStart(node.getPackageName()),
            end = FromEnd(node.getPackageName()),
            name = pkgName
          )
        )
      }
      var r = scan(node.getAnnotations(), p);
      insidePackage = true
      r = scanAndReduce(node.getPackageName(), p, r);
      insidePackage = false
      return r;
    }
    override def visitIdentifier(node: IdentifierTree, p: Unit): TreePath = {
      if (!insidePackage) {
        addName(
          node.getName(),
          identifierSuffix,
          sourcePositions.getStartPosition(cu, node)
        )
      }
      super.visitIdentifier(node, p)
    }
    private def withScope[T](body: => T): T = {
      val oldLocalVariableScope = this.localVariableScope
      this.localVariableScope = new SyntacticScope(this.localVariableScope)
      val result = body
      this.localVariableScope = oldLocalVariableScope
      result
    }
    private def isErrorName(name: String): Boolean = {
      name == "<error>"
    }
    private def scanAndReduce(node: Tree, p: Unit, r: TreePath): TreePath = {
      return reduce(scan(node, p), r);
    }
    private def scanAndReduceIterable(
        nodes: java.lang.Iterable[_ <: Tree],
        p: Unit,
        r: TreePath
    ): TreePath = {
      return reduce(scan(nodes, p), r);
    }
  }

  private def reportError(
      e: Throwable,
      position: Option[l.Position]
  ): Unit = {
    val diagnostics: collection.Seq[String] = parseTask match {
      case None => Nil
      case Some(task) =>
        for {
          d <- task.diagnostics.getDiagnostics().asScala
          pos <- new l.Position(
            d.getLineNumber.intValue - 1,
            d.getColumnNumber.intValue - 1
          ).toMeta(input).toList
        } yield pos.formatMessage(s"${d.getKind()}", s"${e.getMessage()}")
    }
    val diagnosticsContent =
      if (diagnostics.nonEmpty)
        s"""|diagnostics:
            |${diagnostics.mkString("\n")}
            |""".stripMargin
      else ""
    val content = position
      .flatMap(_.toMeta(input))
      .map(pos =>
        CompilerRangeParamsUtils.fromPos(pos, EmptyCancelToken).printed()
      )
      .map(content => s"""|
                          |file contentj:
                          |```java
                          |$content
                          |```
                          |""".stripMargin)
      .getOrElse(s"""|
                     |file content:
                     |```java
                     |${input.text}
                     |```
                     |""".stripMargin)

    rc.unsanitized.create(
      new Report(
        name = "mtags-javac-error",
        text = s"""|error in javac parser$content
                   |$diagnosticsContent
                   |""".stripMargin,
        error = Some(e),
        path = Some(new URI(input.path)),
        shortSummary = s"JavacMtags error processing ${input.path}",
        id = Some(input.path)
      )
    )
  }
}

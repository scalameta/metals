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
import javax.tools.SimpleJavaFileObject
import javax.tools.StandardLocation

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.inputs.Position
import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.internal.metals.Report
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.SymbolInformation.Kind
import scala.meta.internal.semanticdb.SymbolInformation.Property
import scala.meta.pc.reports.ReportContext

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.tree.NewClassTree
import com.sun.source.tree.PackageTree
import com.sun.source.tree.Tree
import com.sun.source.tree.Tree.Kind.INTERFACE
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePath
import com.sun.source.util.TreePathScanner
import com.sun.source.util.Trees
import com.sun.tools.javac.api.JavacTrees
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.parser.ParserFactory
import com.sun.tools.javac.tree.{JCTree => javacTree}
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import com.sun.tools.javac.util.Options

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

  def index(
      input: Input.VirtualFile,
      includeMembers: Boolean
  )(implicit rc: ReportContext): MtagsIndexer =
    new JavacMtags(input, includeMembers)

  // Directly construct a parser factory instead of going via javac getTask()
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
    Options.instance(ctx).put("--enable-preview", "true")
    Options.instance(ctx).put("allowStringFolding", "false")
    fileManager.setLocation(
      StandardLocation.PLATFORM_CLASS_PATH,
      ju.Collections.emptyList()
    )
    fileManager.setContext(ctx)
    ctx.put(classOf[JavaFileManager], fileManager)
    ParseTask(ParserFactory.instance(ctx), fileManager, diagnostics, ctx)
  }

  private class SourceFileObject(src: String, uri: URI)
      extends SimpleJavaFileObject(uri, JavaFileObject.Kind.SOURCE) {
    override def getCharContent(
        ignoreEncodingErrors: Boolean
    ): CharSequence = src
  }

  private def makeSourceFileObject(
      code: String,
      uri: URI
  ): SourceFileObject = {
    val relativeUri =
      if (uri.getScheme() == "jar") {
        val parts = uri.getSchemeSpecificPart().split("!")
        if (parts.length == 2) URI.create(parts(1)) else uri
      } else uri
    new SourceFileObject(code, relativeUri)
  }
}

class JavacMtags(
    val input: Input.VirtualFile,
    includeMembers: Boolean,
    keepDocComments: Boolean = false,
    // When true, private constructors are excluded from emission and from
    // disambiguator computation. Used by JavadocIndexer so that the parameter-
    // name lookup keyed by `<init>(+N)` matches the Scala compiler's view of
    // Java constructors (which excludes private ones).
    filterPrivateConstructors: Boolean = false
)(implicit rc: ReportContext)
    extends MtagsIndexer { mtags =>
  import JavacMtags._

  private var parseTask = Option.empty[ParseTask]

  override def language: Language = Language.JAVA

  override def indexRoot(): Unit = {
    var fileManager: Option[JavaFileManager] = None
    try {
      val context = new Context()
      val task = JavacMtags.createParserFactory(context)
      this.parseTask = Some(task)
      fileManager = Some(task.fileManager)
      val source =
        JavacMtags.makeSourceFileObject(input.text, URI.create(input.path))
      Log.instance(context).useSource(source)
      val parser = task.factory.newParser(
        input.text,
        keepDocComments, // keepDocComments
        true, // keepEndPos
        true // keepLineMap
      )
      val cu = parser.parseCompilationUnit()
      cu.sourcefile = source
      onCompilationUnit(cu)
      val trees = JavacTrees.instance(context)
      val visitor = new Visitor(cu, trees)
      visitor.scan(cu, ())
    } catch {
      case NonFatal(e) =>
        reportError(e)
    } finally {
      fileManager.foreach(_.close())
    }
  }

  // Hook for subclasses (e.g. JavadocIndexer) to inspect the parsed compilation
  // unit (e.g. its import declarations) once, before any member is visited.
  protected def onCompilationUnit(cu: CompilationUnitTree): Unit = {}

  // Hook for subclasses (e.g. JavadocIndexer).
  // `sym` is the SemanticDB symbol for the declaration.
  protected def onClass(
      sym: String,
      name: String,
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {}

  protected def onMethod(
      sym: String,
      name: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {}

  protected def onConstructor(
      sym: String,
      params: List[String],
      typeParams: List[String],
      docComment: Option[String]
  ): Unit = {}

  // Reports each declaration's doc-comment owner (what its relative links
  // resolve against) and start offset, so go-to-definition can map a cursor in a
  // leading doc comment to the declaration it documents (scalameta/metals#3383).
  protected def onDeclaration(contextOwner: String, startOffset: Int): Unit = {}

  private class Visitor(cu: CompilationUnitTree, trees: Trees)
      extends TreePathScanner[TreePath, Unit] {
    private val sourcePositions = trees.getSourcePositions()

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
          if (isMatch && j == name.length()) {
            val pos = Position.Range(mtags.input, i, i + name.length)
            return pos
          }
          i += j
        } else {
          i += 1
        }
      }
      Position.None
    }

    private def isPrivateConstructor(method: MethodTree): Boolean =
      method.getName().toString() == "<init>" &&
        method.getModifiers().getFlags().contains(Modifier.PRIVATE)

    private val disambiguators = new ju.HashMap[MethodTree, Int]()
    private def updateDisambiguators(node: ClassTree): Unit = {
      // Per the SemanticDB spec, the disambiguator is the definition order of
      // the symbol where static methods are sorted to the end. This quirk is
      // because the Scala compiler doesn't preserve the original order of
      // methods in the Java class, it groups static methods into the "module
      // symbol" (companion object) even if Java doesn't have companion objects.
      // Private constructors are excluded from both the output and
      // disambiguation counting to preserve compatibility.
      val sorted = node
        .getMembers()
        .asScala
        .collect {
          case m: MethodTree
              if !filterPrivateConstructors || !isPrivateConstructor(m) =>
            m
        }
        .sortBy(m => m.getModifiers().getFlags().contains(Modifier.STATIC))
      val counters = new ju.HashMap[String, Int]()
      for (method <- sorted) {
        val name = method.getName().toString()
        val next = counters.getOrDefault(name, -1) + 1
        counters.put(name, next)
        if (next > 0) {
          disambiguators.put(method, next)
        }
      }
    }

    // Uses javac's internal DocCommentTable to retrieve Javadoc comments.
    // When keepDocComments=true, the parser populates this table during
    // parsing. This is more robust than manual backward text scanning,
    // which can fail with annotations or non-Javadoc block comments
    // between the doc comment and the declaration.
    private def getDocComment(node: Tree): Option[String] =
      (cu, node) match {
        case (unit: javacTree.JCCompilationUnit, jcNode: javacTree)
            if keepDocComments =>
          for {
            docs <- Option(unit.docComments)
            comment <- Option(docs.getComment(jcNode))
          } yield comment.getText
        case _ => None
      }

    private def extractParamNames(node: MethodTree): List[String] = {
      node.getParameters().asScala.map(_.getName().toString()).toList
    }

    private def extractTypeParamNames(node: ClassTree): List[String] = {
      node.getTypeParameters().asScala.map(_.getName().toString()).toList
    }

    private def extractMethodTypeParamNames(node: MethodTree): List[String] = {
      node.getTypeParameters().asScala.map(_.getName().toString()).toList
    }

    private val recordMembers = new ju.LinkedHashSet[VariableTree]()
    private val enumMembers = new ju.LinkedHashMap[VariableTree, NewClassTree]()

    override def visitClass(node: ClassTree, p: Unit): TreePath = {
      val name = node.getSimpleName().toString()
      if (name.isEmpty() || name == "<error>") {
        // Anonymous or error class - just scan contents
        return super.visitClass(node, p)
      }
      if (node.getKind() == Tree.Kind.RECORD) {
        // Records cannot have instance fields (JLS 8.10.3), so any
        // non-static VariableTree in getMembers() is a record component.
        // We filter out static fields to avoid misidentifying them as
        // record components (which get emitted as accessor methods).
        node.getMembers().asScala.foreach {
          case v: VariableTree
              if !v.getModifiers().getFlags().contains(Modifier.STATIC) =>
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
            case INTERFACE => Kind.INTERFACE
            case _ => Kind.CLASS
          },
          if (node.getKind() == Tree.Kind.ENUM) Property.ENUM.value
          else 0
        )
        node.getTypeParameters().forEach { tparam =>
          mtags.withOwner() {
            val tparamName = tparam.getName().toString()
            mtags.tparam(
              tparamName,
              findNameRange(
                start = tparam
                  .getAnnotations()
                  .asScala
                  .lastOption match {
                  case None => FromStart(tparam)
                  case Some(value) => FromEnd(value)
                },
                end = FromEnd(tparam),
                name = tparamName
              ),
              kind = Kind.TYPE_PARAMETER,
              properties = 0
            )
          }
        }

        val typeParams = extractTypeParamNames(node)
        onClass(currentOwner, name, typeParams, getDocComment(node))
        // A class's own doc resolves relative links against the class itself.
        onDeclaration(
          currentOwner,
          sourcePositions.getStartPosition(cu, node).intValue()
        )

        lazy val constructorCount = node.getMembers().asScala.count {
          case method: MethodTree =>
            val ctorName = method.getName()
            ctorName.length() > 0 && ctorName.charAt(0) == '<'
          case _ => false
        }
        val isImplicitConstructor =
          node.getKind() == Tree.Kind.RECORD ||
            (node.getKind() == Tree.Kind.CLASS && constructorCount == 0) ||
            // The implicit enum constructor could technically be dropped, but
            // we include it for compatibility with semanticdb-javac.
            (node.getKind() == Tree.Kind.ENUM && constructorCount == 0)
        if (isImplicitConstructor) {
          mtags.withOwner() {
            mtags.ctor(
              if (constructorCount == 0) "()" else s"(+$constructorCount)",
              pos,
              0
            )
          }
        }

        // Scan members
        var r = scan(node.getModifiers(), p)
        r = scanAndReduceIterable(node.getTypeParameters(), p, r)
        r = scanAndReduce(node.getExtendsClause(), p, r)
        r = scanAndReduceIterable(node.getImplementsClause(), p, r)
        r = scanAndReduceIterable(node.getMembers(), p, r)
        r
      }
    }

    override def visitMethod(node: MethodTree, p: Unit): TreePath = {
      if (!includeMembers) {
        return null
      }
      val name = node.getName().toString()
      if (name == "<error>") {
        return null
      }
      if (filterPrivateConstructors && isPrivateConstructor(node)) {
        return null
      }
      mtags.withOwner() {
        // The enclosing class, captured before `method` pushes the method as owner.
        val enclosingClass = currentOwner
        val sym = mtags.method(
          name = name,
          disambiguator = disambiguators.getOrDefault(node, 0) match {
            case 0 => "()"
            case n => s"(+$n)"
          },
          pos = findNameRange(
            start = Option(node.getReturnType())
              .orElse(
                node.getModifiers().getAnnotations().asScala.lastOption
              )
              .map(FromEnd(_))
              .getOrElse(FromStart(node)),
            end = node
              .getParameters()
              .asScala
              .headOption
              .orElse(node.getThrows().asScala.headOption)
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
            if (name == "<init>") Kind.CONSTRUCTOR
            else Kind.METHOD
        )
        if (name == "<init>") {
          onConstructor(
            sym,
            extractParamNames(node),
            extractMethodTypeParamNames(node),
            getDocComment(node)
          )
        } else {
          onMethod(
            sym,
            name,
            extractParamNames(node),
            extractMethodTypeParamNames(node),
            getDocComment(node)
          )
        }
        onDeclaration(
          enclosingClass,
          sourcePositions.getStartPosition(cu, node).intValue()
        )
        null // don't scan method body
      }
    }

    override def visitVariable(node: VariableTree, p: Unit): TreePath = {
      if (!includeMembers) {
        return null
      }
      val name = node.getName().toString()
      if (name == "<error>") {
        return null
      }
      val enumMemberInitializer = enumMembers.remove(node)
      val isEnumMember = enumMemberInitializer != null
      val isRecordMember = recordMembers.contains(node)

      mtags.withOwner() {
        // Skip local variables (inside method bodies)
        if (!mtags.currentOwner.endsWith(").")) {
          // The enclosing class — a field's doc resolves relative links against it.
          val enclosingClass = mtags.currentOwner
          val pos = findNameRange(
            start = Option(node.getType()) match {
              case Some(value)
                  if sourcePositions.getEndPosition(cu, value) >= 0 =>
                FromEnd(value)
              case _ => FromStart(node)
            },
            end = Option(node.getInitializer()) match {
              case Some(value)
                  if sourcePositions.getStartPosition(cu, value) >= 0 &&
                    sourcePositions.getEndPosition(cu, value) >= 0 =>
                FromStart(value)
              case _ => FromEnd(node)
            },
            name = name
          )
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
              kind = Kind.FIELD,
              properties =
                if (isEnumMember) Property.ENUM.value
                else 0
            )
          }
          onDeclaration(
            enclosingClass,
            sourcePositions.getStartPosition(cu, node).intValue()
          )
        }
      }
      null // don't scan variable initializers
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
      var r = scan(node.getAnnotations(), p)
      r = scanAndReduce(node.getPackageName(), p, r)
      r
    }

    private def scanAndReduce(node: Tree, p: Unit, r: TreePath): TreePath = {
      reduce(scan(node, p), r)
    }
    private def scanAndReduceIterable(
        nodes: java.lang.Iterable[_ <: Tree],
        p: Unit,
        r: TreePath
    ): TreePath = {
      reduce(scan(nodes, p), r)
    }
  }

  private def reportError(e: Throwable): Unit = {
    try {
      val diagnosticMessages: collection.Seq[String] = parseTask match {
        case None => Nil
        case Some(task) =>
          task.diagnostics.getDiagnostics().asScala.map { d =>
            s"${d.getKind()}: ${d.getMessage(null)}"
          }
      }
      val diagnosticsContent =
        if (diagnosticMessages.nonEmpty)
          s"""|diagnostics:
              |${diagnosticMessages.mkString("\n")}
              |""".stripMargin
        else ""

      rc.unsanitized()
        .create(() =>
          new Report(
            name = "mtags-javac-error",
            text = s"""|error in javac parser
                       |$diagnosticsContent
                       |""".stripMargin,
            error = Some(e),
            path = java.util.Optional.of(new URI(input.path)),
            shortSummary = s"JavacMtags error processing ${input.path}",
            id = java.util.Optional.of(input.path)
          )
        )
    } catch {
      case NonFatal(_) =>
    }
  }
}

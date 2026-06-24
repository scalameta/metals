package scala.meta.internal.parsing

import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Locale
import javax.lang.model.element.Modifier
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.{Diagnostic => JavaDiagnostic}

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.jpc.Positions
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.io.AbsolutePath

import com.sun.source.tree.ClassTree
import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.LineMap
import com.sun.source.tree.MethodTree
import com.sun.source.tree.Tree
import com.sun.source.tree.VariableTree
import com.sun.source.util.TreePathScanner
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.parser.ParserFactory
import com.sun.tools.javac.tree.{JCTree => JavacJCTree}
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import com.sun.tools.javac.util.Options
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.{lsp4j => l}

class JavaTrees(buffers: Buffers) {

  private val trees = TrieMap.empty[AbsolutePath, CompilationUnitTree]

  def didClose(source: AbsolutePath): Unit = {
    trees.remove(source)
  }

  def findEnclosingJavaClass(
      source: AbsolutePath,
      pos: l.Position,
  ): Option[JavaClassInfo] =
    for {
      text <- text(source)
      tree <- get(source)
      result <- {
        val visitor = new EnclosingClassFinder(tree, text, pos)
        visitor.scan(tree, ())
        visitor.result
      }
    } yield result

  def findEnclosingJavaMethod(
      source: AbsolutePath,
      pos: l.Position,
  ): Option[EnclosingMethod] =
    for {
      text <- text(source)
      tree <- get(source)
      result <- {
        val visitor = new EnclosingMethodFinder(tree, text, pos, source)
        visitor.scan(tree, ())
        visitor.result
      }
    } yield result

  private def text(source: AbsolutePath): Option[String] =
    buffers.get(source).orElse(source.readTextOpt)

  def get(source: AbsolutePath): Option[CompilationUnitTree] =
    trees.get(source).orElse {
      text(source).flatMap(text => parse(source, text).map(_.tree))
    }

  def didChange(source: AbsolutePath): List[l.Diagnostic] = {
    text(source) match {
      case Some(text) =>
        parse(source, text) match {
          case Some(parsed) if parsed.diagnostics.isEmpty =>
            trees(source) = parsed.tree
            Nil
          case Some(parsed) =>
            trees.remove(source)
            parsed.diagnostics
          case None =>
            trees.remove(source)
            Nil
        }
      case None =>
        trees.remove(source)
        Nil
    }
  }

  private def parse(
      source: AbsolutePath,
      text: String,
  ): Option[ParsedJavaCompilationUnit] = {
    try {
      val input = Input.VirtualFile(source.toURI.toString, text)
      val context = new Context()
      val silentWriter =
        new java.io.PrintWriter(new java.io.StringWriter())
      Log.preRegister(context, silentWriter)
      context.put(Log.outKey, silentWriter)
      context.put(Log.errKey, silentWriter)
      val diagnostics = new DiagnosticCollector[JavaFileObject]()
      context.put(
        classOf[javax.tools.DiagnosticListener[_]],
        diagnostics,
      )
      val fileManager =
        new JavacFileManager(context, false, StandardCharsets.UTF_8)
      try {
        Options.instance(context).put("--enable-preview", "true")
        Options.instance(context).put("allowStringFolding", "false")
        fileManager.setLocation(
          javax.tools.StandardLocation.PLATFORM_CLASS_PATH,
          java.util.Collections.emptyList(),
        )
        fileManager.setContext(context)
        context.put(classOf[javax.tools.JavaFileManager], fileManager)
        val parserFactory = ParserFactory.instance(context)
        val javaSource =
          SourceJavaFileObject.make(text, URI.create(input.path))
        Log.instance(context).useSource(javaSource)
        val parser = parserFactory.newParser(
          text,
          false,
          true,
          true,
        )
        val cu = parser.parseCompilationUnit()
        cu.asInstanceOf[JavacJCTree.JCCompilationUnit].sourcefile = javaSource
        Some(
          ParsedJavaCompilationUnit(
            tree = cu,
            diagnostics =
              diagnostics.getDiagnostics().asScala.toList.map { diagnostic =>
                toLspDiagnostic(diagnostic)
              },
          )
        )
      } finally {
        fileManager.close()
      }
    } catch {
      case NonFatal(_) => None
    }
  }

  private def toLspDiagnostic(
      diagnostic: JavaDiagnostic[_ <: JavaFileObject]
  ): l.Diagnostic = {
    val position =
      if (
        diagnostic.getPosition() == JavaDiagnostic.NOPOS ||
        diagnostic.getLineNumber() < 1 ||
        diagnostic.getColumnNumber() < 1
      ) {
        new l.Position(0, 0)
      } else {
        new l.Position(
          diagnostic.getLineNumber().toInt - 1,
          diagnostic.getColumnNumber().toInt - 1,
        )
      }

    val severity = diagnostic.getKind() match {
      case JavaDiagnostic.Kind.ERROR => DiagnosticSeverity.Error
      case JavaDiagnostic.Kind.WARNING |
          JavaDiagnostic.Kind.MANDATORY_WARNING =>
        DiagnosticSeverity.Warning
      case JavaDiagnostic.Kind.NOTE => DiagnosticSeverity.Information
      case JavaDiagnostic.Kind.OTHER => DiagnosticSeverity.Hint
    }

    new l.Diagnostic(
      new l.Range(position, position),
      diagnostic.getMessage(Locale.ENGLISH),
      severity,
      "javac",
    )
  }

  private class EnclosingMethodFinder(
      cu: CompilationUnitTree,
      text: String,
      targetPos: l.Position,
      source: AbsolutePath,
  ) extends TreePathScanner[Unit, Unit] {
    private val lineMap = cu.getLineMap()
    private val endPosTable =
      cu.asInstanceOf[JavacJCTree.JCCompilationUnit].endPositions
    private var _result: Option[EnclosingMethod] = None

    def result: Option[EnclosingMethod] = _result

    private def startPosOf(tree: Tree): Long =
      tree.asInstanceOf[JavacJCTree].getStartPosition().toLong
    private def endPosOf(tree: Tree): Long =
      tree.asInstanceOf[JavacJCTree].getEndPosition(endPosTable).toLong

    override def visitMethod(
        node: MethodTree,
        p: Unit,
    ): Unit = {
      val nodeStart = startPosOf(node)
      val nodeEnd = endPosOf(node)

      if (positionContains(lineMap, targetPos, nodeStart, nodeEnd)) {
        val methodName = node.getName().toString()
        val displayName =
          if (methodName == "<init>") {
            val parent = Option(getCurrentPath().getParentPath().getLeaf())
            parent match {
              case Some(parent: ClassTree) =>
                parent.getSimpleName().toString()
              case _ => methodName
            }
          } else methodName

        val returnType = node.getReturnType()
        val nameStartPos =
          if (returnType != null) endPosOf(returnType)
          else nodeStart
        val params = node.getParameters()
        val nameEndPos =
          if (!params.isEmpty()) startPosOf(params.get(0))
          else if (node.getBody() != null) startPosOf(node.getBody())
          else nodeEnd

        val nameRange =
          findNameRange(
            lineMap,
            text,
            nameStartPos,
            nameEndPos,
            displayName,
          )
        val bodyRange =
          Positions.toLspRange(lineMap, nodeStart, nodeEnd, text)
        nameRange.foreach { range =>
          _result = Some(EnclosingMethod(range, bodyRange, source))
        }
        super.visitMethod(node, p)
      }

    }
  }

  private class EnclosingClassFinder(
      cu: CompilationUnitTree,
      text: String,
      targetPos: l.Position,
  ) extends TreePathScanner[Unit, Unit] {
    private val lineMap = cu.getLineMap()
    private val endPosTable =
      cu.asInstanceOf[JavacJCTree.JCCompilationUnit].endPositions
    private var _result: Option[JavaClassInfo] = None

    def result: Option[JavaClassInfo] = _result

    private def startPosOf(tree: Tree): Long =
      tree.asInstanceOf[JavacJCTree].getStartPosition().toLong
    private def endPosOf(tree: Tree): Long =
      tree.asInstanceOf[JavacJCTree].getEndPosition(endPosTable).toLong

    override def visitClass(
        node: ClassTree,
        p: Unit,
    ): Unit = {
      val nodeStart = startPosOf(node)
      val nodeEnd = endPosOf(node)
      if (positionContains(lineMap, targetPos, nodeStart, nodeEnd)) {
        if (node.getKind() == Tree.Kind.CLASS) {
          val name = node.getSimpleName().toString()
          if (name.nonEmpty) {
            for {
              nameRange <- findNameRange(
                lineMap,
                text,
                nodeStart,
                nodeEnd,
                name,
              )
              nameEndOffset = lineMap.getPosition(
                nameRange.getEnd().getLine() + 1L,
                nameRange.getEnd().getCharacter() + 1L,
              )
              body <- bodyRange(nameEndOffset, nodeEnd)
            } {
              val members =
                node
                  .getMembers()
                  .asScala
                  .collect {
                    case method: MethodTree
                        if method.getName().toString() == "<init>" =>
                      treeRange(method).map(range =>
                        JavaMemberInfo(
                          kind = JavaMemberKind.Constructor,
                          range = range.range,
                          startOffset = range.startOffset,
                          endOffset = range.endOffset,
                          parametersCount = Some(method.getParameters().size()),
                        )
                      )
                    case method: MethodTree =>
                      treeRange(method).map(range =>
                        JavaMemberInfo(
                          kind = JavaMemberKind.Method,
                          range = range.range,
                          startOffset = range.startOffset,
                          endOffset = range.endOffset,
                          parametersCount = Some(method.getParameters().size()),
                        )
                      )
                    case field: VariableTree =>
                      treeRange(field).map(range =>
                        JavaMemberInfo(
                          kind = JavaMemberKind.Field,
                          range = range.range,
                          startOffset = range.startOffset,
                          endOffset = range.endOffset,
                          parametersCount = None,
                        )
                      )
                  }
                  .flatten
                  .toList
              _result = Some(
                JavaClassInfo(
                  name = name,
                  nameRange = nameRange,
                  bodyRange = body.range,
                  bodyStartOffset = body.startOffset,
                  bodyEndOffset = body.endOffset,
                  modifiers = node.getModifiers().getFlags().asScala.toSet,
                  members = members,
                )
              )
            }
          }
        }
        super.visitClass(node, p)
      }
    }

    private def bodyRange(
        startPos: Long,
        endPos: Long,
    ): Option[JavaRange] = {
      if (endPos < 0) None
      else
        openingBraceOffset(startPos, endPos).map { openBrace =>
          JavaRange(
            Positions.toLspRange(
              lineMap,
              openBrace + 1L,
              endPos - 1L,
              text,
            ),
            startOffset = (openBrace + 1L).toInt,
            endOffset = (endPos - 1L).toInt,
          )
        }
    }

    private def openingBraceOffset(
        startPos: Long,
        endPos: Long,
    ): Option[Long] = {
      if (startPos < 0 || endPos < 0) None
      else {
        var offset = startPos.toInt
        val searchEnd = Math.min(endPos.toInt, text.length())
        var result: Option[Long] = None
        while (offset < searchEnd && result.isEmpty) {
          if (text.charAt(offset) == '{') {
            result = Some(offset.toLong)
          }
          offset += 1
        }
        result
      }
    }

    private def treeRange(
        tree: Tree
    ): Option[JavaRange] = {
      val treeStart = startPosOf(tree)
      val treeEnd = endPosOf(tree)
      if (treeStart < 0 || treeEnd < 0) None
      else
        Some(
          JavaRange(
            Positions.toLspRange(lineMap, treeStart, treeEnd, text),
            startOffset = treeStart.toInt,
            endOffset = treeEnd.toInt,
          )
        )
    }
  }

  private def positionContains(
      lineMap: LineMap,
      targetPos: l.Position,
      startPos: Long,
      endPos: Long,
  ): Boolean = {
    if (startPos < 0 || endPos < 0) false
    else {
      val startLine = lineMap.getLineNumber(startPos).toInt - 1
      val endLine = lineMap.getLineNumber(endPos).toInt - 1
      val startCol = lineMap.getColumnNumber(startPos).toInt - 1
      val endCol = lineMap.getColumnNumber(endPos).toInt - 1

      val targetLine = targetPos.getLine()
      val targetCol = targetPos.getCharacter()

      if (targetLine < startLine || targetLine > endLine) false
      else if (targetLine == startLine && targetCol < startCol) false
      else if (targetLine == endLine && targetCol > endCol) false
      else true
    }
  }

  private def findNameRange(
      lineMap: LineMap,
      text: String,
      startPos: Long,
      endPos: Long,
      name: String,
  ): Option[l.Range] = {
    if (startPos < 0 || endPos < 0) None
    else {
      var offset = startPos.toInt
      val searchEnd = Math.min(endPos.toInt, text.length())
      var result: Option[l.Range] = None
      while (offset < searchEnd && result.isEmpty) {
        val endOffset = offset + name.length()
        val isWordBoundary =
          endOffset >= text.length() ||
            !Character.isJavaIdentifierPart(text.charAt(endOffset))
        if (
          Character.isJavaIdentifierStart(text.charAt(offset)) &&
          text.startsWith(name, offset) &&
          isWordBoundary
        ) {
          result = Some(
            Positions.toLspRange(
              lineMap,
              offset.toLong,
              endOffset.toLong,
              text,
            )
          )
        }
        offset += 1
      }
      result
    }
  }

  private case class ParsedJavaCompilationUnit(
      tree: CompilationUnitTree,
      diagnostics: List[l.Diagnostic],
  )

  private case class JavaRange(
      range: l.Range,
      startOffset: Int,
      endOffset: Int,
  )

}

case class EnclosingMethod(
    nameRange: l.Range,
    bodyRange: l.Range,
    source: AbsolutePath,
)

case class JavaClassInfo(
    name: String,
    nameRange: l.Range,
    bodyRange: l.Range,
    bodyStartOffset: Int,
    bodyEndOffset: Int,
    modifiers: Set[Modifier],
    members: List[JavaMemberInfo],
)

case class JavaMemberInfo(
    kind: JavaMemberKind,
    range: l.Range,
    startOffset: Int,
    endOffset: Int,
    parametersCount: Option[Int],
)

sealed trait JavaMemberKind
object JavaMemberKind {
  case object Field extends JavaMemberKind
  case object Method extends JavaMemberKind
  case object Constructor extends JavaMemberKind
}

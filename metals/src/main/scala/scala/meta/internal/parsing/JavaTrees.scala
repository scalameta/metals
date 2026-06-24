package scala.meta.internal.parsing

import java.net.URI
import java.nio.charset.StandardCharsets
import javax.lang.model.element.Modifier
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject

import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.jpc.JavaDiagnostics
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

  def findEnclosingJavaVariable(
      source: AbsolutePath,
      pos: l.Position,
  ): Option[JavaVariableInfo] =
    for {
      text <- text(source)
      tree <- get(source)
      result <- {
        val visitor = new EnclosingVariableFinder(tree, text, pos)
        visitor.scan(tree, ())
        visitor.result
      }
    } yield result

  /**
   * Returns the insertion point right after the leading fields of `cls`.
   *
   * This is the default location for inserting generated members (default
   * constructor, getters, setters, ...).
   */
  def insertPointAfterFields(
      cls: JavaClassInfo,
      text: String,
  ): InsertPoint = {
    val start = cls.members
      .takeWhile(_.kind == JavaMemberKind.Field)
      .lastOption
      .map(m => PositionWithOffset(m.range.getEnd(), m.range.endOffset))
      .getOrElse(
        PositionWithOffset(cls.bodyRange.getStart(), cls.bodyRange.startOffset)
      )

    val expandedEnd = cls.members
      .filter(_.range.startOffset > start.offset)
      .minByOption(_.range.startOffset)
      .map(m => PositionWithOffset(m.range.getStart(), m.range.startOffset))
      .getOrElse(
        PositionWithOffset(cls.bodyRange.getEnd(), cls.bodyRange.endOffset)
      )

    val canExpand =
      expandedEnd.offset <= text.length &&
        text.substring(start.offset, expandedEnd.offset).forall(_.isWhitespace)

    val end = if (canExpand) expandedEnd else start
    InsertPoint(
      new l.Range(start.position, end.position),
      start.offset,
      end.offset,
      isInsertion = !canExpand,
    )
  }

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
        val lineMap = cu.getLineMap()
        Some(
          ParsedJavaCompilationUnit(
            tree = cu,
            diagnostics =
              diagnostics.getDiagnostics().asScala.toList.map { diagnostic =>
                JavaDiagnostics.toLspDiagnostic(lineMap, text, diagnostic)
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

  private abstract class EnclosingFinder[T](
      cu: CompilationUnitTree,
      text: String,
      targetPos: l.Position,
  ) extends TreePathScanner[Unit, Unit] {
    protected val lineMap: LineMap = cu.getLineMap()
    protected val pos = new TreePositions(cu)
    protected val targetOffset: Int = lspPositionToOffset(lineMap, targetPos)
    protected var _result: Option[T] = None

    def result: Option[T] = _result

    protected def treeRange(tree: Tree): Option[JavaRange] = {
      val treeStart = pos.startPos(tree)
      val treeEnd = pos.endPos(tree)
      if (treeStart < 0 || treeEnd < 0) None
      else
        Some(
          JavaRange(
            Positions.toLspRange(lineMap, treeStart, treeEnd, text),
            startOffset = treeStart,
            endOffset = treeEnd,
          )
        )
    }
  }

  private class EnclosingMethodFinder(
      cu: CompilationUnitTree,
      text: String,
      targetPos: l.Position,
      source: AbsolutePath,
  ) extends EnclosingFinder[EnclosingMethod](cu, text, targetPos) {

    override def visitMethod(
        node: MethodTree,
        p: Unit,
    ): Unit = {
      val nodeStart = pos.startPos(node)
      val nodeEnd = pos.endPos(node)

      if (positionContains(targetOffset, nodeStart, nodeEnd)) {
        val methodName = node.getName().toString()
        val displayName =
          if (methodName == "<init>")
            getCurrentPath().getParentPath().getLeaf() match {
              case parent: ClassTree => parent.getSimpleName().toString()
              case _ => methodName
            }
          else methodName

        val returnType = node.getReturnType()
        val nameStartPos =
          if (returnType != null) pos.endPos(returnType)
          else nodeStart
        val params = node.getParameters()
        val nameEndPos =
          if (!params.isEmpty()) pos.startPos(params.get(0))
          else if (node.getBody() != null) pos.startPos(node.getBody())
          else nodeEnd

        val bodyRange =
          Positions.toLspRange(lineMap, nodeStart, nodeEnd, text)
        findNameRange(lineMap, text, nameStartPos, nameEndPos, displayName)
          .foreach { javaRange =>
            _result = Some(EnclosingMethod(javaRange.range, bodyRange, source))
          }
        super.visitMethod(node, p)
      }

    }
  }

  private class EnclosingVariableFinder(
      cu: CompilationUnitTree,
      text: String,
      targetPos: l.Position,
  ) extends EnclosingFinder[JavaVariableInfo](cu, text, targetPos) {

    override def visitVariable(
        node: VariableTree,
        p: Unit,
    ): Unit = {
      val nodeStart = pos.startPos(node)
      val nodeEnd = pos.endPos(node)

      if (positionContains(targetOffset, nodeStart, nodeEnd)) {
        val fieldName = node.getName().toString()
        val fieldType = node.getType().toString()
        val modifiers = node.getModifiers().getFlags().asScala.toSet
        val range = treeRange(node)
        range.foreach { range =>
          _result =
            Some(JavaVariableInfo(fieldName, fieldType, range, modifiers))
        }
      }
      super.visitVariable(node, p)
    }
  }

  private class EnclosingClassFinder(
      cu: CompilationUnitTree,
      text: String,
      targetPos: l.Position,
  ) extends EnclosingFinder[JavaClassInfo](cu, text, targetPos) {

    override def visitClass(
        node: ClassTree,
        p: Unit,
    ): Unit = {
      val nodeStart = pos.startPos(node)
      val nodeEnd = pos.endPos(node)
      if (positionContains(targetOffset, nodeStart, nodeEnd)) {
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
              body <- bodyRange(nameRange.endOffset, nodeEnd)
            } {
              val members =
                node
                  .getMembers()
                  .asScala
                  .collect {
                    case method: MethodTree =>
                      val methodName = method.getName().toString()
                      val kind =
                        if (methodName == "<init>")
                          JavaMemberKind.Constructor
                        else JavaMemberKind.Method
                      treeRange(method).map(range =>
                        JavaMemberInfo(
                          kind = kind,
                          range = range,
                          parametersCount = Some(method.getParameters().size()),
                          name = Some(methodName),
                        )
                      )
                    case field: VariableTree =>
                      treeRange(field).map(range =>
                        JavaMemberInfo(
                          kind = JavaMemberKind.Field,
                          range = range,
                          parametersCount = None,
                          name = Some(field.getName().toString()),
                        )
                      )
                  }
                  .flatten
                  .toList
              _result = Some(
                JavaClassInfo(
                  name = name,
                  nameRange = nameRange.range,
                  bodyRange = body,
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
        startPos: Int,
        endPos: Int,
    ): Option[JavaRange] = {
      if (startPos < 0 || endPos < 0) None
      else {
        var offset = startPos
        val searchEnd = Math.min(endPos, text.length())
        var openBrace: Option[Int] = None
        while (offset < searchEnd && openBrace.isEmpty) {
          if (text.charAt(offset) == '{') openBrace = Some(offset)
          offset += 1
        }
        openBrace.map { brace =>
          JavaRange(
            Positions.toLspRange(lineMap, brace + 1, endPos - 1, text),
            startOffset = brace + 1,
            endOffset = endPos - 1,
          )
        }
      }
    }
  }

  private class TreePositions(cu: CompilationUnitTree) {
    private val endPosTable =
      cu.asInstanceOf[JavacJCTree.JCCompilationUnit].endPositions
    def startPos(tree: Tree): Int =
      tree.asInstanceOf[JavacJCTree].getStartPosition()
    def endPos(tree: Tree): Int =
      tree.asInstanceOf[JavacJCTree].getEndPosition(endPosTable)
  }

  private def positionContains(
      targetOffset: Int,
      startPos: Int,
      endPos: Int,
  ): Boolean =
    startPos >= 0 && endPos >= 0 &&
      startPos <= targetOffset && targetOffset <= endPos

  private def lspPositionToOffset(
      lineMap: LineMap,
      pos: l.Position,
  ): Int =
    lineMap.getPosition(pos.getLine() + 1L, pos.getCharacter() + 1L).toInt

  private def findNameRange(
      lineMap: LineMap,
      text: String,
      startPos: Int,
      endPos: Int,
      name: String,
  ): Option[JavaRange] = {
    if (startPos < 0 || endPos < 0) None
    else {
      val searchEnd = Math.min(endPos, text.length())
      (startPos until searchEnd)
        .find { offset =>
          val endOffset = offset + name.length()
          // Char at `offset` is a valid identifier start.
          Character.isJavaIdentifierStart(text.charAt(offset)) &&
          // Substring at `offset` matches the target name exactly.
          text.startsWith(name, offset) &&
          // Left boundary: previous char does not continue an identifier.
          (offset == 0 ||
            !Character.isJavaIdentifierPart(text.charAt(offset - 1))) &&
          // Right boundary: next char is end-of-text or does not continue an identifier.
          (endOffset >= text.length() ||
            !Character.isJavaIdentifierPart(text.charAt(endOffset)))
        }
        .map { offset =>
          val endOffset = offset + name.length()
          JavaRange(
            Positions.toLspRange(lineMap, offset, endOffset, text),
            startOffset = offset,
            endOffset = endOffset,
          )
        }
    }
  }

  private case class ParsedJavaCompilationUnit(
      tree: CompilationUnitTree,
      diagnostics: List[l.Diagnostic],
  )

}

case class JavaRange(
    range: l.Range,
    startOffset: Int,
    endOffset: Int,
) {
  def getStart(): l.Position = range.getStart()
  def getEnd(): l.Position = range.getEnd()
}

case class EnclosingMethod(
    nameRange: l.Range,
    bodyRange: l.Range,
    source: AbsolutePath,
)

case class InsertPoint(
    range: l.Range,
    startOffset: Int,
    endOffset: Int,
    isInsertion: Boolean,
)

private case class PositionWithOffset(position: l.Position, offset: Int)

case class JavaVariableInfo(
    name: String,
    typ: String,
    range: JavaRange,
    modifiers: Set[Modifier],
) {
  def isFinal: Boolean = modifiers.contains(Modifier.FINAL)
  def isStatic: Boolean = modifiers.contains(Modifier.STATIC)
}

case class JavaClassInfo(
    name: String,
    nameRange: l.Range,
    bodyRange: JavaRange,
    modifiers: Set[Modifier],
    members: List[JavaMemberInfo],
)

case class JavaMemberInfo(
    kind: JavaMemberKind,
    range: JavaRange,
    parametersCount: Option[Int],
    name: Option[String],
)

sealed trait JavaMemberKind
object JavaMemberKind {
  case object Field extends JavaMemberKind
  case object Method extends JavaMemberKind
  case object Constructor extends JavaMemberKind
}

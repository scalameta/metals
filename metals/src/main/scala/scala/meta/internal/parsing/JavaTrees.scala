package scala.meta.internal.parsing

import java.net.URI
import java.nio.charset.StandardCharsets
import javax.lang.model.element.Modifier
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject

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
import com.sun.source.util.SourcePositions
import com.sun.source.util.TreePathScanner
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.parser.ParserFactory
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import com.sun.tools.javac.util.Options
import org.eclipse.{lsp4j => l}

class JavaTrees(buffers: Buffers) {

  private val trees = TrieMap.empty[AbsolutePath, CachedJavaTree]

  def didChange(source: AbsolutePath): Unit = {
    if (source.isJavaFilename) cached(source)
  }

  def didClose(source: AbsolutePath): Unit = {
    trees.remove(source)
  }

  def findEnclosingJavaClass(
      source: AbsolutePath,
      pos: l.Position,
  ): Option[JavaClassInfo] = {
    cached(source).flatMap { cached =>
      val visitor = new EnclosingClassFinder(cached, pos)
      visitor.scan(cached.tree, ())
      visitor.result
    }
  }

  def findEnclosingJavaMethod(
      source: AbsolutePath,
      pos: l.Position,
  ): Option[EnclosingMethod] = {
    cached(source).flatMap { cached =>
      val visitor = new EnclosingMethodFinder(cached, pos, source)
      visitor.scan(cached.tree, ())
      visitor.result
    }
  }

  private def text(source: AbsolutePath): Option[String] =
    buffers.get(source).orElse(source.readTextOpt)

  private def cached(source: AbsolutePath): Option[CachedJavaTree] = {
    text(source).flatMap { text =>
      val textHash = text.hashCode()
      trees.get(source) match {
        case Some(cached) if cached.textHash == textHash =>
          Some(cached)
        case _ =>
          parse(source, text).map { parsed =>
            if (buffers.contains(source)) {
              trees(source) = parsed
            }
            parsed
          }
      }
    }
  }

  private def parse(
      source: AbsolutePath,
      text: String,
  ): Option[CachedJavaTree] = {
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
        cu.asInstanceOf[com.sun.tools.javac.tree.JCTree.JCCompilationUnit]
          .sourcefile = javaSource
        val javacTrees =
          com.sun.tools.javac.api.JavacTrees.instance(context)
        Some(
          CachedJavaTree(
            textHash = text.hashCode(),
            tree = cu,
            sourcePositions = javacTrees.getSourcePositions(),
            text = text,
          )
        )
      } finally {
        fileManager.close()
      }
    } catch {
      case NonFatal(_) => None
    }
  }

  private class EnclosingMethodFinder(
      cached: CachedJavaTree,
      targetPos: l.Position,
      source: AbsolutePath,
  ) extends TreePathScanner[Unit, Unit] {
    private val cu = cached.tree
    private val sourcePositions = cached.sourcePositions
    private val lineMap = cu.getLineMap()
    private var _result: Option[EnclosingMethod] = None

    def result: Option[EnclosingMethod] = _result

    override def visitMethod(
        node: MethodTree,
        p: Unit,
    ): Unit = {
      val startPos = sourcePositions.getStartPosition(cu, node)
      val endPos = sourcePositions.getEndPosition(cu, node)

      if (positionContains(lineMap, targetPos, startPos, endPos)) {
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
          if (returnType != null)
            sourcePositions.getEndPosition(cu, returnType)
          else
            sourcePositions.getStartPosition(cu, node)
        val params = node.getParameters()
        val nameEndPos =
          if (!params.isEmpty())
            sourcePositions.getStartPosition(cu, params.get(0))
          else if (node.getBody() != null)
            sourcePositions.getStartPosition(cu, node.getBody())
          else
            sourcePositions.getEndPosition(cu, node)

        val nameRange =
          findNameRange(
            lineMap,
            cached.text,
            nameStartPos,
            nameEndPos,
            displayName,
          )
        val bodyRange =
          Positions.toLspRange(lineMap, startPos, endPos, cached.text)
        nameRange.foreach { range =>
          _result = Some(EnclosingMethod(range, bodyRange, source))
        }
        super.visitMethod(node, p)
      }

    }
  }

  private class EnclosingClassFinder(
      cached: CachedJavaTree,
      targetPos: l.Position,
  ) extends TreePathScanner[Unit, Unit] {
    private val cu = cached.tree
    private val sourcePositions = cached.sourcePositions
    private val lineMap = cu.getLineMap()
    private var _result: Option[JavaClassInfo] = None

    def result: Option[JavaClassInfo] = _result

    override def visitClass(
        node: ClassTree,
        p: Unit,
    ): Unit = {
      val startPos = sourcePositions.getStartPosition(cu, node)
      val endPos = sourcePositions.getEndPosition(cu, node)
      if (positionContains(lineMap, targetPos, startPos, endPos)) {
        if (node.getKind() == Tree.Kind.CLASS) {
          val name = node.getSimpleName().toString()
          if (name.nonEmpty) {
            for {
              nameRange <- findNameRange(
                lineMap,
                cached.text,
                startPos,
                endPos,
                name,
              )
              nameEndOffset = lineMap.getPosition(
                nameRange.getEnd().getLine() + 1L,
                nameRange.getEnd().getCharacter() + 1L,
              )
              bodyRange <- bodyRange(nameEndOffset, endPos)
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
                          range = range,
                          parametersCount = Some(method.getParameters().size()),
                        )
                      )
                    case method: MethodTree =>
                      treeRange(method).map(range =>
                        JavaMemberInfo(
                          kind = JavaMemberKind.Method,
                          range = range,
                          parametersCount = Some(method.getParameters().size()),
                        )
                      )
                    case field: VariableTree =>
                      treeRange(field).map(range =>
                        JavaMemberInfo(
                          kind = JavaMemberKind.Field,
                          range = range,
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
                  bodyRange = bodyRange,
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
    ): Option[l.Range] = {
      if (endPos < 0) None
      else
        openingBraceOffset(startPos, endPos).map { openBrace =>
          Positions.toLspRange(
            lineMap,
            openBrace + 1L,
            endPos - 1L,
            cached.text,
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
        val searchEnd = Math.min(endPos.toInt, cached.text.length())
        var result: Option[Long] = None
        while (offset < searchEnd && result.isEmpty) {
          if (cached.text.charAt(offset) == '{') {
            result = Some(offset.toLong)
          }
          offset += 1
        }
        result
      }
    }

    private def treeRange(
        tree: Tree
    ): Option[l.Range] = {
      val startPos = sourcePositions.getStartPosition(cu, tree)
      val endPos = sourcePositions.getEndPosition(cu, tree)
      if (startPos < 0 || endPos < 0) None
      else Some(Positions.toLspRange(lineMap, startPos, endPos, cached.text))
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

  private case class CachedJavaTree(
      textHash: Int,
      tree: CompilationUnitTree,
      sourcePositions: SourcePositions,
      text: String,
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
    modifiers: Set[Modifier],
    members: List[JavaMemberInfo],
)

case class JavaMemberInfo(
    kind: JavaMemberKind,
    range: l.Range,
    parametersCount: Option[Int],
)

sealed trait JavaMemberKind
object JavaMemberKind {
  case object Field extends JavaMemberKind
  case object Method extends JavaMemberKind
  case object Constructor extends JavaMemberKind
}

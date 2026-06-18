package scala.meta.internal.parsing

import java.net.URI
import java.nio.charset.StandardCharsets
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject

import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.jpc.SourceJavaFileObject
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.io.AbsolutePath

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.MethodTree
import com.sun.source.util.TreePathScanner
import com.sun.tools.javac.file.JavacFileManager
import com.sun.tools.javac.parser.ParserFactory
import com.sun.tools.javac.util.Context
import com.sun.tools.javac.util.Log
import com.sun.tools.javac.util.Options
import org.eclipse.{lsp4j => l}

class JavaTrees(buffers: Buffers) {

  def findEnclosingJavaMethod(
      source: AbsolutePath,
      pos: l.Position,
  ): Option[EnclosingMethod] = {
    val textOpt = buffers.get(source).orElse(source.readTextOpt)
    textOpt.flatMap { text =>
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

          val visitor = new EnclosingMethodFinder(cu, javacTrees, pos, source)
          visitor.scan(cu, ())
          val result = visitor.result
          result
        } finally {
          fileManager.close()
        }
      } catch {
        case NonFatal(_) => None
      }
    }
  }

  private class EnclosingMethodFinder(
      cu: CompilationUnitTree,
      javacTrees: com.sun.tools.javac.api.JavacTrees,
      targetPos: l.Position,
      source: AbsolutePath,
  ) extends TreePathScanner[Unit, Unit] {
    private val sourcePositions = javacTrees.getSourcePositions()
    private val lineMap = cu.getLineMap()
    private var _result: Option[EnclosingMethod] = None

    def result: Option[EnclosingMethod] = _result

    private def positionContains(
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

    override def visitMethod(
        node: MethodTree,
        p: Unit,
    ): Unit = {
      val startPos = sourcePositions.getStartPosition(cu, node)
      val endPos = sourcePositions.getEndPosition(cu, node)

      if (positionContains(startPos, endPos)) {
        val methodName = node.getName().toString()
        val displayName =
          if (methodName == "<init>") {
            val parent = Option(getCurrentPath().getParentPath().getLeaf())
            parent match {
              case Some(parent: com.sun.source.tree.ClassTree) =>
                parent.getSimpleName().toString()
              case _ => methodName
            }
          } else methodName

        val nameRange = findNameRange(node, displayName)
        val bodyRange = new l.Range(
          new l.Position(
            lineMap.getLineNumber(startPos).toInt - 1,
            lineMap.getColumnNumber(startPos).toInt - 1,
          ),
          new l.Position(
            lineMap.getLineNumber(endPos).toInt - 1,
            lineMap.getColumnNumber(endPos).toInt - 1,
          ),
        )
        nameRange.foreach { range =>
          _result = Some(EnclosingMethod(range, bodyRange, source))
        }
        super.visitMethod(node, p)
      }

    }

    private def findNameRange(
        node: MethodTree,
        name: String,
    ): Option[l.Range] = {
      val returnType = node.getReturnType()
      val startPos =
        if (returnType != null)
          sourcePositions.getEndPosition(cu, returnType)
        else
          sourcePositions.getStartPosition(cu, node)
      val params = node.getParameters()
      val endPos =
        if (!params.isEmpty())
          sourcePositions.getStartPosition(cu, params.get(0))
        else if (node.getBody() != null)
          sourcePositions.getStartPosition(cu, node.getBody())
        else
          sourcePositions.getEndPosition(cu, node)

      if (startPos < 0 || endPos < 0) return None

      val text = cu.getSourceFile().getCharContent(true).toString()
      var i = startPos.toInt
      val searchEnd = Math.min(endPos.toInt, text.length())

      while (i < searchEnd) {
        if (
          Character.isJavaIdentifierStart(text.charAt(i)) &&
          text.substring(i).startsWith(name)
        ) {
          val startLine = lineMap.getLineNumber(i).toInt - 1
          val startCol = lineMap.getColumnNumber(i).toInt - 1
          val endOffset = i + name.length()
          val endLine = lineMap.getLineNumber(endOffset).toInt - 1
          val endCol = lineMap.getColumnNumber(endOffset).toInt - 1
          return Some(
            new l.Range(
              new l.Position(startLine, startCol),
              new l.Position(endLine, endCol),
            )
          )
        }
        i += 1
      }
      None
    }
  }

}

case class EnclosingMethod(
    nameRange: l.Range,
    bodyRange: l.Range,
    source: AbsolutePath,
)

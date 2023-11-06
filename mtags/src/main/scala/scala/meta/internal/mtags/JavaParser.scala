package scala.meta.internal.mtags

import java.net.URI
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

import scala.jdk.CollectionConverters._

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.SourcePositions
import com.sun.source.util.Trees

object JavaParser {

  private val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()

  private val noopDiagnosticListener = new DiagnosticListener[JavaFileObject] {

    // ignore errors
    override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = ()
  }

  def parse(text: String, uriStr: String): Option[ParseTrees] = {
    val uri = URI.create(uriStr)
    val javaFileObject = SourceJavaFileObject.make(text, uri)
    val javacTask = COMPILER
      .getTask(
        null,
        null,
        noopDiagnosticListener,
        Nil.asJava,
        null,
        List(javaFileObject).asJava
      )
      .asInstanceOf[JavacTask]
    val iter = javacTask.parse().iterator
    def trees = Trees.instance(javacTask)
    if (iter.hasNext) Some(ParseTrees(iter.next(), trees)) else None
  }

}

case class ParseTrees(unit: CompilationUnitTree, trees: Trees) {
  val sourcePosition: SourcePositions = trees.getSourcePositions()

  def getStart(tree: Tree): Int =
    sourcePosition.getStartPosition(unit, tree).toInt
  def getEnd(tree: Tree): Int = sourcePosition.getEndPosition(unit, tree).toInt

}

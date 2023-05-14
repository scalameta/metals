package scala.meta.internal.pc

import java.net.URI
import javax.tools.JavaCompiler
import javax.tools.ToolProvider

import scala.jdk.CollectionConverters._

import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath

class JavaMetalsGlobal(
    val search: SymbolSearch,
    val metalsConfig: PresentationCompilerConfig
) {

  private val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()

  def compilationTask(sourceCode: String, uri: URI): JavacTask = {
    val javaFileObject = SourceJavaFileObject.make(sourceCode, uri)

    COMPILER
      .getTask(
        null,
        null,
        null,
        null,
        null,
        List(javaFileObject).asJava
      )
      .asInstanceOf[JavacTask]
  }

  var lastVisitedParentTrees: List[TreePath] = Nil

  def scanner(task: JavacTask): JavaTreeScanner = {
    val elems = task.parse()
    task.analyze()
    val root = elems.iterator().next()

    new JavaTreeScanner(task, root)
  }

  def compilerTreeNode(
      scanner: JavaTreeScanner,
      position: CursorPosition
  ): Option[TreePath] = {
    scanner.scan(scanner.root, position)
    lastVisitedParentTrees = scanner.lastVisitedParentTrees

    lastVisitedParentTrees.headOption
  }
}

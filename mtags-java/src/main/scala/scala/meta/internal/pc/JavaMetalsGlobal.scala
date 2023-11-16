package scala.meta.internal.pc

import java.io.File
import java.io.Writer
import java.net.URI
import java.nio.file.Path
import javax.tools.Diagnostic
import javax.tools.DiagnosticListener
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

import scala.jdk.CollectionConverters._

import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.SymbolSearch

import com.sun.source.util.JavacTask
import com.sun.source.util.TreePath

class JavaMetalsGlobal(
    val search: SymbolSearch,
    val metalsConfig: PresentationCompilerConfig,
    val classpath: Seq[Path]
) {
  var lastVisitedParentTrees: List[TreePath] = Nil

  def compilerTreeNode(
      scanner: JavaTreeScanner,
      position: CursorPosition
  ): Option[TreePath] = {
    scanner.scan(scanner.root, position)
    lastVisitedParentTrees = scanner.lastVisitedParentTrees
    lastVisitedParentTrees.headOption
  }

  def compilationTask(sourceCode: String, uri: URI): JavacTask = {
    val javaFileObject = SourceJavaFileObject.make(sourceCode, uri)
    JavaMetalsGlobal.classpathCompilationTask(
      javaFileObject,
      None,
      List("-classpath", classpath.mkString(File.pathSeparator))
    )
  }

}

object JavaMetalsGlobal {

  private val COMPILER: JavaCompiler = ToolProvider.getSystemJavaCompiler()

  private val noopDiagnosticListener = new DiagnosticListener[JavaFileObject] {

    // ignore errors since presentation compiler will have a lot of transient ones
    override def report(diagnostic: Diagnostic[_ <: JavaFileObject]): Unit = ()
  }

  def makeFileObject(file: File): JavaFileObject = {
    val fileManager = COMPILER.getStandardFileManager(null, null, null)
    val files = fileManager.getJavaFileObjectsFromFiles(List(file).asJava)
    files.iterator().next()
  }

  def baseCompilationTask(sourceCode: String, uri: URI): JavacTask = {
    val javaFileObject = SourceJavaFileObject.make(sourceCode, uri)
    JavaMetalsGlobal.classpathCompilationTask(javaFileObject, None, Nil)
  }
  def classpathCompilationTask(
      javaFileObject: JavaFileObject,
      out: Option[Writer],
      allOptions: List[String]
  ): JavacTask = {
    COMPILER
      .getTask(
        out.orNull,
        null,
        noopDiagnosticListener,
        allOptions.asJava,
        null,
        List(javaFileObject).asJava
      )
      .asInstanceOf[JavacTask]
  }

  def scanner(task: JavacTask): JavaTreeScanner = {
    val elems = task.parse()
    task.analyze()
    val root = elems.iterator().next()

    new JavaTreeScanner(task, root)
  }
}

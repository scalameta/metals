package scala.meta.internal.jpc

import scala.jdk.CollectionConverters._

import scala.meta.internal.pc.AutoImportsResultImpl
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.OffsetParams

import com.sun.source.util.Trees

class JavaAutoImportProvider(
    val compiler: JavaMetalsCompiler,
    val params: OffsetParams,
    val toImportName: String
) {
  private val NameStartRegex = """^(\w+).*""".r
  def autoImports(): List[AutoImportsResult] = {
    val query = toImportName match {
      case NameStartRegex(name) => name
      case _ => toImportName
    }
    params.checkCanceled()
    val compile = compiler.compilationTask(params).withAnalyzePhase()
    params.checkCanceled()
    val trees = Trees.instance(compile.task)
    val task = compile.task
    val cu = compile.cu
    val scanner = new JavaTreeScanner(compiler.logger, task, cu)
    val position =
      CursorPosition(params.offset(), params.offset(), params.offset())
    val node = compiler.compilerTreeNode(scanner, position)
    for {
      path <- node.iterator
      fqn <- compiler.doSearch(query).iterator
      // Only suggest imports for exact matches
      if fqn.endsWith(s".$toImportName")
      edit = new JavaAutoImportEditor(path, trees, fqn).textEdit()
      pkg = fqn.split('.').dropRight(1).mkString(".")
    } yield AutoImportsResultImpl(
      pkg,
      List(edit).asJava,
      java.util.Optional.empty()
    )
  }.toList
}

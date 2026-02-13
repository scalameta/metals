package scala.meta.internal.jpc

import scala.jdk.CollectionConverters._

import scala.meta.internal.pc.AutoImportsResultImpl
import scala.meta.pc.AutoImportsResult
import scala.meta.pc.OffsetParams

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
    for {
      fqn <- compiler.doSearch(query).iterator
      // Only suggest imports for exact matches
      if fqn.endsWith(s".$toImportName")
      edit = new JavaAutoImportEditor(params.text(), fqn).textEdit()
      pkg = fqn.split('.').dropRight(1).mkString(".")
    } yield AutoImportsResultImpl(
      pkg,
      List(edit).asJava,
      java.util.Optional.empty()
    )
  }.toList
}

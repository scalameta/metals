package scala.meta.internal.pc

import scala.meta.internal.semanticdb.scalac.SemanticdbConfig
import scala.meta.internal.{semanticdb => s}

class SemanticdbTextDocumentProvider(val compiler: MetalsGlobal) {
  import compiler._
  def textDocument(
      filename: String,
      code: String
  ): s.TextDocument = {
    val unit = addCompilationUnit(
      code = code,
      filename = filename,
      cursor = None
    )
    typeCheck(unit)
    semanticdbOps.config = SemanticdbConfig.parse(
      List(
        "-P:semanticdb:synthetics:on",
        "-P:semanticdb:symbols:none",
        "-P:semanticdb:text:on"
      ),
      _ => (),
      compiler.reporter,
      SemanticdbConfig.default
    )
    import semanticdbOps._
    val document = unit.toTextDocument
    document.withUri(filename)
  }
}

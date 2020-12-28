package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

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
    import semanticdbOps._
    // This cache is never updated in semanticdb and will contain the old source
    gSourceFileInputCache.remove(unit.source)
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
    val document = unit.toTextDocument
    val fileUri = Paths.get(new URI(filename))
    compiler.workspace
      .map { workspacePath =>
        val relativeUri = workspacePath.relativize(fileUri).toString()
        document.withUri(relativeUri)
      }
      .getOrElse(document)
  }
}

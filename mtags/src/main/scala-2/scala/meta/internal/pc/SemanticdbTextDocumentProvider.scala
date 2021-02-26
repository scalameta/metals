package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.meta.dialects
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.scalac.SemanticdbConfig
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

class SemanticdbTextDocumentProvider(val compiler: MetalsGlobal)
    extends WorksheetSemanticdbProvider {
  import compiler._

  def textDocument(
      uri: URI,
      code: String
  ): s.TextDocument = {
    val filePath = AbsolutePath(Paths.get(uri))
    val validCode = removeMagicImports(code, filePath)

    val unit = addCompilationUnit(
      code = validCode,
      filename = uri.toString(),
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

    val explicitDialect = if (filePath.isSbt) {
      Some(dialects.Sbt1)
    } else if (filePath.isScalaScript) {
      Some(dialects.Scala213.withAllowToplevelTerms(true))
    } else {
      None
    }
    val document = unit.toTextDocument(explicitDialect)
    compiler.workspace
      .flatMap { workspacePath =>
        scala.util.Try(workspacePath.relativize(filePath.toNIO)).toOption
      }
      .map { relativeUri =>
        document.withUri(relativeUri.toString())
      }
      .getOrElse(document)
  }
}

package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.util.Properties

import scala.meta.dialects
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.scalac.SemanticdbConfig
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.meta.tokenizers.TokenizeException

class SemanticdbTextDocumentProvider(
    val compiler: MetalsGlobal,
    semanticdbCompilerOptions: List[String]
) extends WorksheetSemanticdbProvider {
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
      semanticdbCompilerOptions,
      _ => (),
      compiler.reporter,
      SemanticdbConfig.default
    )

    val explicitDialect = if (filePath.isSbt) {
      Some(dialects.Sbt1)
    } else if (filePath.isMill || filePath.isScalaScript) {
      Some(dialects.Scala213.withAllowToplevelTerms(true))
    } else {
      None
    }
    // we recalculate md5, since there seems to be issue with newlines sometimes
    val document =
      try {
        unit
          .toTextDocument(explicitDialect)
          .withMd5(MD5.compute(code))
          .withDiagnostics(compiler.semanticdbDiagnosticsOf(unit))
      } catch {
        case _: TokenizeException | _: ParseException =>
          s.TextDocument.defaultInstance
      }

    compiler.workspace
      .flatMap { workspacePath =>
        scala.util.Try(workspacePath.relativize(filePath.toNIO)).toOption
      }
      .map { relativeUri =>
        val relativeString =
          if (Properties.isWin) relativeUri.toString().replace("\\", "/")
          else relativeUri.toString()
        document.withUri(relativeString)
      }
      .getOrElse(document)
  }
}

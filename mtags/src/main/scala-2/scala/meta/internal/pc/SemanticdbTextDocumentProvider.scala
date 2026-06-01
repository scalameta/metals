package scala.meta.internal.pc

import java.net.URI
import java.nio.file.Paths

import scala.util.Properties

import scala.meta.dialects
import scala.meta.internal.metals.SimpleTimer
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.semanticdb.scalac.SemanticdbConfig
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.meta.parsers.ParseException
import scala.meta.pc.VirtualFileParams
import scala.meta.tokenizers.TokenizeException

class SemanticdbTextDocumentProvider(
    val compiler: MetalsGlobal,
    semanticdbCompilerOptions: List[String]
) extends WorksheetSemanticdbProvider {
  import compiler._

  def textDocument(
      params: VirtualFileParams
  ): s.TextDocument =
    textDocument(
      params.uri(),
      params.text(),
      params.shouldPruneSemanticdb()
    )

  def textDocument(
      uri: URI,
      code: String
  ): s.TextDocument =
    textDocument(uri, code, shouldPruneSemanticdb = false)

  private def textDocument(
      uri: URI,
      code: String,
      shouldPruneSemanticdb: Boolean
  ): s.TextDocument = {
    val filePath = AbsolutePath(Paths.get(uri))
    val validCode = removeMagicImports(code, filePath)
    val shouldPrune =
      shouldPruneSemanticdb && compiler.metalsConfig
        .sourcePathMode()
        .shouldPrune()

    val unit = addCompilationUnit(
      code = validCode,
      filename = uri.toString(),
      cursor = None,
      forceNew = shouldPrune,
      willBeRemovedAfterUsing = shouldPrune
    )
    try {
      if (shouldPrune) {
        PruneLateSourcesComponent.loadedFromSource.add(unit.source.file)
      }
      SimpleTimer.timedThunk(
        "semanticdb:textDocument:typeCheck",
        thresholdMillis = 250
      ) {
        typeCheck(unit)
      }

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
      val document = SimpleTimer.timedThunk(
        "semanticdb:testDocument:toTextDocument",
        thresholdMillis = 250
      ) {
        try {
          val result = unit
            .toTextDocument(explicitDialect)
            .withMd5(MD5.compute(code))
            .withDiagnostics(compiler.semanticdbDiagnosticsOf(unit))
          if (shouldPrune) {
            result.withText("")
          } else {
            result
          }
        } catch {
          case _: TokenizeException | _: ParseException =>
            s.TextDocument.defaultInstance
        }
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
    } finally {
      if (shouldPrune) {
        PruneLateSourcesComponent.loadedFromSource.remove(unit.source.file)
        removeAfterUsing(uri.toString())
      }
    }
  }
}

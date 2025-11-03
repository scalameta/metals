package scala.meta.internal.metals

import java.util

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.{inputs => m}

import org.eclipse.{lsp4j => l}

final class JavaFormattingProvider(
    buffers: Buffers,
    userConfig: () => UserConfiguration,
    buildTargets: BuildTargets,
    client: () => l.services.LanguageClient,
)(implicit ec: ExecutionContext) {
  private lazy val eclipseFormatter =
    new EclipseJavaFormatter(buffers, userConfig, buildTargets)
  private lazy val googleFormatter =
    new GoogleJavaFormatter(client)

  private def fromLSP(input: m.Input): m.Position.Range =
    m.Position.Range(input, 0, input.chars.length)

  def format(
      params: l.DocumentFormattingParams
  ): Future[util.List[l.TextEdit]] = {
    Future {
      val options = params.getOptions
      val path = params.getTextDocument.getUri.toAbsolutePath
      val input = path.toInputFromBuffers(buffers)
      runFormat(path, input, options, fromLSP(input)).asJava
    }
  }

  def formatRange(
      params: l.DocumentRangeFormattingParams
  ): util.List[l.TextEdit] = {
    val options = params.getOptions
    val range = params.getRange
    val path = params.getTextDocument.getUri.toAbsolutePath
    val input = path.toInputFromBuffers(buffers)
    range.toMeta(input) match {
      case Some(rng: m.Position) =>
        runFormat(path, input, options, rng).asJava
      case None =>
        scribe.debug(s"range $range was not found in $path")
        Nil.asJava
    }
  }

  def format(): util.List[l.TextEdit] = java.util.Collections.emptyList()

  private def runFormat(
      path: AbsolutePath,
      input: m.Input.VirtualFile,
      formattingOptions: l.FormattingOptions,
      range: m.Position,
  ): List[l.TextEdit] = {
    userConfig().javaFormatter match {
      case Some(config) if config.isNone =>
        Nil // No-op: Java formatting disabled
      case Some(config) if config.isEclipse =>
        eclipseFormatter.format(path, input, formattingOptions, range)
      case _ => // Default to google-java-format (including None and "google-java-format")
        googleFormatter.format(input)
    }
  }
}

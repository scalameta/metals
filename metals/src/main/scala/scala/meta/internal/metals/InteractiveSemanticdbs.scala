package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.{lsp4j => l}

/**
 * Produces SemanticDBs on-demand by using the presentation compiler.
 *
 * Only used to provide navigation inside external library sources, not used to compile
 * workspace sources.
 *
 * Uses persistent storage to keep track of what external source file is associated
 * with what build target (to determine classpath and compiler options).
 */
final class InteractiveSemanticdbs(
    workspace: AbsolutePath,
    buildTargets: BuildTargets,
    charset: Charset,
    client: MetalsLanguageClient,
    tables: Tables,
    statusBar: StatusBar,
    compilers: () => Compilers,
    clientConfig: ClientConfiguration
) extends Cancelable
    with Semanticdbs {

  private val activeDocument = new AtomicReference[Option[String]](None)
  private val textDocumentCache = Collections.synchronizedMap(
    new java.util.HashMap[AbsolutePath, s.TextDocument]()
  )

  def reset(): Unit = {
    textDocumentCache.clear()
  }

  override def cancel(): Unit = {
    reset()
  }

  override def textDocument(source: AbsolutePath): TextDocumentLookup = {

    def shouldTryCalculateInteractiveSemanticdb = {
      source.isDependencySource(workspace) || source.isSbt || buildTargets
        .inverseSources(source)
        .isEmpty
    }

    if (
      source.isWorksheet || !source.isScalaFilename || !shouldTryCalculateInteractiveSemanticdb
    ) {
      TextDocumentLookup.NotFound(source)
    } else {
      val result = textDocumentCache.compute(
        source,
        (path, existingDoc) => {
          val text = FileIO.slurp(source, charset)
          val sha = MD5.compute(text)
          if (existingDoc == null || existingDoc.md5 != sha)
            compile(path, text)
          else
            existingDoc
        }
      )
      TextDocumentLookup.fromOption(source, Option(result))
    }
  }

  /**
   * Persist relationship between this dependency source and its enclosing build target
   */
  def didDefinition(source: AbsolutePath, result: DefinitionResult): Unit = {
    for {
      destination <- result.definition
      if destination.isDependencySource(workspace)
      buildTarget = buildTargets.inverseSources(source)
    } {
      if (source.isWorksheet) {
        tables.worksheetSources.setWorksheet(destination, source)
      } else {
        buildTarget.foreach { target =>
          tables.dependencySources.setBuildTarget(destination, target)
        }
      }
    }
  }

  /**
   * Unpublish diagnostics for un-focused dependency source, if any, and publish diagnostics
   * for the currently focused source, if any.
   */
  def didFocus(path: AbsolutePath): Unit = {
    activeDocument.get().foreach { uri =>
      client.publishDiagnostics(
        new PublishDiagnosticsParams(uri, Collections.emptyList())
      )
    }
    if (path.isDependencySource(workspace)) {
      textDocument(path).toOption.foreach { doc =>
        val uri = path.toURI.toString()
        activeDocument.set(Some(uri))
        val diagnostics = for {
          diag <- doc.diagnostics
          if diag.severity.isError
          range <- diag.range
        } yield {
          // Use INFO instead of ERROR severity because these diagnostics are published for readonly
          // files of external dependencies so the user cannot fix them.
          val severity = DiagnosticSeverity.Information
          new l.Diagnostic(range.toLSP, diag.message, severity, "scala")
        }
        if (diagnostics.nonEmpty) {
          statusBar.addMessage(partialNavigation(clientConfig.icons))
          client.publishDiagnostics(
            new PublishDiagnosticsParams(uri, diagnostics.asJava)
          )
        }
      }
    } else {
      activeDocument.set(None)
    }
  }

  private def compile(source: AbsolutePath, text: String): s.TextDocument = {
    val fromTarget = for {
      buildTarget <- buildTargets.inverseSources(source)
      pc <- compilers().loadCompiler(buildTarget)
    } yield pc

    val pc = fromTarget
      .orElse {
        tables.worksheetSources
          .getWorksheet(source)
          .flatMap(compilers().loadWorksheetCompiler)
      }
      .getOrElse(compilers().fallbackCompiler)

    val (prependedLinesSize, modifiedText) =
      buildTargets
        .sbtAutoImports(source)
        .fold((0, text))(imports =>
          (imports.size, SbtBuildTool.prependAutoImports(text, imports))
        )

    val uri = source.toURI.toString
    // NOTE(olafur): it's unfortunate that we block on `semanticdbTextDocument`
    // here but to avoid it we would need to refactor the `Semanticdbs` trait,
    // which requires more effort than it's worth.
    val bytes = pc
      .semanticdbTextDocument(uri, modifiedText)
      .get(
        clientConfig.initialConfig.compilers.timeoutDelay,
        clientConfig.initialConfig.compilers.timeoutUnit
      )
    val textDocument = s.TextDocument.parseFrom(bytes)
    if (prependedLinesSize > 0)
      cleanupAutoImports(textDocument, text, prependedLinesSize)
    else textDocument
  }

  private def cleanupAutoImports(
      document: s.TextDocument,
      originalText: String,
      linesSize: Int
  ): s.TextDocument = {

    def adjustRange(range: s.Range): Option[s.Range] = {
      val nextStartLine = range.startLine - linesSize
      val nextEndLine = range.endLine - linesSize
      if (nextEndLine >= 0) {
        val nextRange = range.copy(
          startLine = nextStartLine,
          endLine = nextEndLine
        )
        Some(nextRange)
      } else None
    }

    val adjustedOccurences =
      document.occurrences.flatMap { occurence =>
        occurence.range
          .flatMap(adjustRange)
          .map(r => occurence.copy(range = Some(r)))
      }

    val adjustedDiagnostic =
      document.diagnostics.flatMap { diagnostic =>
        diagnostic.range
          .flatMap(adjustRange)
          .map(r => diagnostic.copy(range = Some(r)))
      }

    val adjustedSynthetic =
      document.synthetics.flatMap { synthetic =>
        synthetic.range
          .flatMap(adjustRange)
          .map(r => synthetic.copy(range = Some(r)))
      }

    s.TextDocument(
      schema = document.schema,
      uri = document.uri,
      text = originalText,
      md5 = MD5.compute(originalText),
      language = document.language,
      symbols = document.symbols,
      occurrences = adjustedOccurences,
      diagnostics = adjustedDiagnostic,
      synthetics = adjustedSynthetic
    )
  }

}

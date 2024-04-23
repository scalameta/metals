package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.Collections

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Success
import scala.util.Try

import scala.meta.internal.builds.SbtBuildTool
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.scalacli.ScalaCliServers
import scala.meta.internal.mtags.MD5
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.Shebang
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath

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
    tables: Tables,
    compilers: () => Compilers,
    clientConfig: ClientConfiguration,
    semanticdbIndexer: () => SemanticdbIndexer,
    javaInteractiveSemanticdb: Option[JavaInteractiveSemanticdb],
    buffers: Buffers,
    scalaCliServers: => ScalaCliServers,
) extends Cancelable
    with Semanticdbs {

  private val textDocumentCache = Collections.synchronizedMap(
    new java.util.HashMap[AbsolutePath, s.TextDocument]()
  )

  def reset(): Unit = {
    textDocumentCache.clear()
  }

  override def cancel(): Unit = {
    reset()
  }

  override def textDocument(
      source: AbsolutePath
  ): TextDocumentLookup = textDocument(source, unsavedContents = None)

  def onClose(path: AbsolutePath): Unit = {
    textDocumentCache.remove(path)
  }

  def textDocument(
      source: AbsolutePath,
      unsavedContents: Option[String],
  ): TextDocumentLookup = {
    def doesNotBelongToBuildTarget = buildTargets.inverseSources(source).isEmpty
    lazy val sourceText =
      buffers.get(source).orElse {
        if (source.exists) Some(source.readText(charset))
        else None
      }
    def shouldTryCalculateInteractiveSemanticdb = {
      source.isLocalFileSystem(workspace) && (
        unsavedContents.isDefined ||
          source.isInReadonlyDirectory(workspace) || // dependencies
          source.isSbt || // sbt files
          source.isWorksheet || // worksheets
          doesNotBelongToBuildTarget || // standalone files
          scalaCliServers.loadedExactly(source) || // scala-cli single files
          sourceText.exists(
            _.startsWith(Shebang.shebang)
          ) // starts with shebang
      ) || source.isJarFileSystem // dependencies
    }

    // anything aside from `*.scala`, `*.sbt`, `*.sc`, `*.java` file
    def isExcludedFile = !source.isScalaFilename && !source.isJavaFilename

    if (isExcludedFile || !shouldTryCalculateInteractiveSemanticdb) {
      TextDocumentLookup.NotFound(source)
    } else {
      val result = textDocumentCache.compute(
        source,
        (path, existingDoc) => {
          unsavedContents.orElse(sourceText) match {
            case None => null
            case Some(text) =>
              val adjustedText =
                if (text.startsWith(Shebang.shebang))
                  "//" + text.drop(2)
                else text
              val sha = MD5.compute(adjustedText)
              if (existingDoc == null || existingDoc.md5 != sha) {
                compile(path, adjustedText) match {
                  case Success(doc) if doc != null =>
                    if (!source.isDependencySource(workspace))
                      semanticdbIndexer().onChange(source, doc)
                    doc
                  case _ => null
                }
              } else
                existingDoc
          }

        },
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

  private def compile(source: AbsolutePath, text: String): Try[s.TextDocument] =
    Try {
      if (source.isJavaFilename)
        javaInteractiveSemanticdb.fold(s.TextDocument())(
          _.textDocument(source, text)
        )
      else scalaCompile(source, text)
    }

  private def scalaCompile(
      source: AbsolutePath,
      text: String,
  ): s.TextDocument = {

    val pc = compilers()
      .loadCompiler(source)
      .orElse {
        // load presentation compiler for sources that were create by a worksheet definition request
        tables.worksheetSources
          .getWorksheet(source)
          .flatMap(compilers().loadWorksheetCompiler)
      }
      .getOrElse {
        // this is highly unlikely since None is only returned for non scala/java files
        throw new RuntimeException(
          s"No presentation compiler found for $source"
        )
      }

    val (prependedLinesSize, modifiedText) =
      Option
        .when(source.isSbt)(
          buildTargets
            .sbtAutoImports(source)
        )
        .flatten
        .fold((0, text))(imports =>
          (imports.size, SbtBuildTool.prependAutoImports(text, imports))
        )

    // NOTE(olafur): it's unfortunate that we block on `semanticdbTextDocument`
    // here but to avoid it we would need to refactor the `Semanticdbs` trait,
    // which requires more effort than it's worth.
    val bytes = Await
      .result(
        pc.semanticdbTextDocument(source.toURI, modifiedText).asScala,
        Duration(
          clientConfig.initialConfig.compilers.timeoutDelay,
          clientConfig.initialConfig.compilers.timeoutUnit,
        ),
      )
    val textDocument = {
      val doc = s.TextDocument.parseFrom(bytes)
      if (doc.text.isEmpty()) doc.withText(text)
      else doc
    }
    if (prependedLinesSize > 0)
      cleanupAutoImports(textDocument, text, prependedLinesSize)
    else textDocument
  }

  private def cleanupAutoImports(
      document: s.TextDocument,
      originalText: String,
      linesSize: Int,
  ): s.TextDocument = {

    def adjustRange(range: s.Range): Option[s.Range] = {
      val nextStartLine = range.startLine - linesSize
      val nextEndLine = range.endLine - linesSize
      if (nextEndLine >= 0) {
        val nextRange = range.copy(
          startLine = nextStartLine,
          endLine = nextEndLine,
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
      synthetics = adjustedSynthetic,
    )
  }

}

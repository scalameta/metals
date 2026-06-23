package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap

import scala.util.Success
import scala.util.Try

import scala.meta.infra.FeatureFlag
import scala.meta.infra.FeatureFlagProvider
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
 * Normally only used to provide navigation inside external library sources, not
 * to compile workspace sources. The exception is MBT mode, where the build
 * server does not emit on-disk SemanticDB for workspace sources, so we compute
 * it interactively here as well.
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
    semanticdbIndexer: () => SemanticdbIndexer,
    buffers: Buffers,
    scalaCliServers: => ScalaCliServers,
    featureFlags: FeatureFlagProvider,
    isMbt: () => Boolean = () => false,
) extends Cancelable
    with Semanticdbs {

  private val textDocumentCache =
    new ConcurrentHashMap[AbsolutePath, s.TextDocument]()

  private lazy val isInteractiveSemanticdbEnabled: Boolean =
    "true" == System.getProperty("metals.interactive.semanticdb", "false") ||
      featureFlags.readBoolean(FeatureFlag.INTERACTIVE_SEMANTICDB).orElse(false)

  def reset(): Unit = {
    textDocumentCache.clear()
  }

  override def cancel(): Unit = {
    reset()
  }

  override def textDocument(
      source: AbsolutePath,
      requestInteractive: Boolean,
  ): TextDocumentLookup =
    textDocument(source, unsavedContents = None, requestInteractive)

  def onClose(path: AbsolutePath): Unit = {
    textDocumentCache.remove(path)
  }

  def textDocument(
      source: AbsolutePath,
      unsavedContents: Option[String],
      requestInteractive: Boolean,
  ): TextDocumentLookup = {
    def doesNotBelongToBuildTarget = buildTargets.inverseSources(source).isEmpty
    lazy val sourceText =
      buffers.get(source).orElse {
        if (source.exists) Some(source.readText(charset))
        else None
      }
    def shouldTryCalculateInteractiveSemanticdb = {
      source.isLocalFileSystem(workspace) && (
        isInteractiveSemanticdbEnabled || requestInteractive ||
          unsavedContents.isDefined ||
          source.isInReadonlyDirectory(workspace) || // dependencies
          source.isSbt || // sbt files
          source.isMill || // mill files
          source.isProtoFilename || // protobuf files (no semanticdb-scalac)
          source.isWorksheet || // worksheets
          doesNotBelongToBuildTarget && buffers.contains(
            source
          ) || // standalone files that are opened
          isMbt() || // MBT build server does not emit on-disk SemanticDB
          scalaCliServers.loadedExactly(source) || // scala-cli single files
          sourceText.exists(
            _.startsWith(Shebang.shebang)
          ) // starts with shebang
      ) || source.isJarFileSystem // dependencies
    }

    // anything aside from `*.scala`, `*.sbt`, `*.mill`, `*.sc`, `*.java`, `*.proto` file
    def isExcludedFile =
      !source.isScalaFilename && !source.isJavaFilename && !source.isProtoFilename

    if (isExcludedFile || !shouldTryCalculateInteractiveSemanticdb) {
      TextDocumentLookup.NotFound(source)
    } else {
      // Don't hold the map lock during compilation to avoid blocking all
      // threads. Instead, check the cache first and only compile outside
      // the lock if needed.
      val text = unsavedContents.orElse(sourceText)
      val result = text match {
        case None =>
          textDocumentCache.remove(source)
          null
        case Some(rawText) =>
          val adjustedText =
            if (rawText.startsWith(Shebang.shebang))
              "//" + rawText.drop(2)
            else rawText
          val sha = MD5.compute(adjustedText)
          val existingDoc = textDocumentCache.get(source)
          if (existingDoc != null && existingDoc.md5 == sha) {
            existingDoc
          } else {
            compile(source, adjustedText) match {
              case Success(doc) if doc != null =>
                textDocumentCache.put(source, doc)
                if (!source.isDependencySource(workspace))
                  semanticdbIndexer().onChange(source, doc)
                doc
              case _ =>
                textDocumentCache.remove(source)
                null
            }
          }
      }
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
      compilers().semanticdbTextDocument(source, text)
    }

}

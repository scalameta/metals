package scala.meta.internal.metals

import java.nio.charset.Charset
import java.util.Collections

import scala.util.Success
import scala.util.Try

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
    semanticdbIndexer: () => SemanticdbIndexer,
    javaInteractiveSemanticdb: JavaInteractiveSemanticdb,
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
          source.isMill || // mill files
          source.isWorksheet || // worksheets
          doesNotBelongToBuildTarget || // standalone files
          scalaCliServers.loadedExactly(source) || // scala-cli single files
          sourceText.exists(
            _.startsWith(Shebang.shebang)
          ) // starts with shebang
      ) || source.isJarFileSystem // dependencies
    }

    // anything aside from `*.scala`, `*.sbt`, `*.mill`, `*.sc`, `*.java` file
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
        javaInteractiveSemanticdb.textDocument(source, text)
      else compilers().semanticdbTextDocument(source, text)
    }

}

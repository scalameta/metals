package scala.meta.internal.metals

import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.ScalacOptionsItem
import java.net.URI
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.{lsp4j => l}
import scala.collection.concurrent.TrieMap
import scala.concurrent.ExecutionContext
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.internal.io.FileIO
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.mtags.TextDocumentLookup
import scala.meta.internal.tokenizers.PlatformTokenizerCache
import scala.meta.internal.{semanticdb => s}
import scala.meta.io.AbsolutePath
import scala.tools.nsc.interactive.Global

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
    messages: Messages,
    statusBar: StatusBar
)(implicit ec: ExecutionContext)
    extends Cancelable
    with Semanticdbs {
  import messages._
  private val activeDocument = new AtomicReference[Option[String]](None)
  // Not ConcurrentHashMap since it can hit on deadlocks inside computeIfAbsent
  // when the "if absent" function is slow.
  private val globalCache = Collections.synchronizedMap(
    new java.util.HashMap[BuildTargetIdentifier, Global]()
  )
  private val textDocumentCache = Collections.synchronizedMap(
    new java.util.HashMap[AbsolutePath, s.TextDocument]()
  )
  // keys are created files in .metals/readonly/ and values are the original paths
  // in *-sources.jar files.
  private val readonlyToSource = TrieMap.empty[AbsolutePath, AbsolutePath]

  def toFileOnDisk(path: AbsolutePath): AbsolutePath = {
    val disk = path.toFileOnDisk(workspace)
    if (disk != path) {
      readonlyToSource(disk) = path
    }
    disk
  }

  def reset(): Unit = {
    textDocumentCache.clear()
    globalCache.values.asScala.foreach(_.askShutdown())
    globalCache.clear()
  }

  override def cancel(): Unit = {
    reset()
  }

  override def textDocument(source: AbsolutePath): TextDocumentLookup = {
    if (!source.toLanguage.isScala ||
      !source.isDependencySource(workspace)) {
      TextDocumentLookup.NotFound(source)
    } else {
      val result =
        textDocumentCache.computeIfAbsent(source, path => compile(path).orNull)
      TextDocumentLookup.fromOption(source, Option(result))
    }
  }

  /** Persist relationship between this dependency source and its enclosing build target */
  def didDefinition(source: AbsolutePath, result: DefinitionResult): Unit = {
    for {
      destination <- result.definition
      if destination.isDependencySource(workspace)
      buildTarget <- buildTargets.inverseSources(source).orElse {
        tables.dependencySources.getBuildTarget(source)
      }
    } {
      tables.dependencySources.setBuildTarget(destination, buildTarget)
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
        activeDocument.set(Some(doc.uri))
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
          statusBar.addMessage(PartialNavigation)
          client.publishDiagnostics(
            new PublishDiagnosticsParams(doc.uri, diagnostics.asJava)
          )
        }
      }
    } else {
      activeDocument.set(None)
    }
  }

  private def compile(source: AbsolutePath): Option[s.TextDocument] = {
    for {
      buildTarget <- getBuildTarget(source)
      global <- Option(
        globalCache.computeIfAbsent(buildTarget, x => newGlobal(x).orNull)
      )
    } yield {
      val text = FileIO.slurp(source, charset)
      val uri = source.toURI.toString
      val textDocument = InteractiveSemanticdb
        .toTextDocument(
          global,
          code = text,
          filename = uri,
          timeout = TimeUnit.SECONDS.toMillis(15),
          options = List(
            "-P:semanticdb:synthetics:on",
            "-P:semanticdb:symbols:none",
            "-P:semanticdb:text:on"
          )
        )
        .withUri(uri) // semanticdb-scalac does weird URI encoding
      textDocumentCache.put(source, textDocument)
      PlatformTokenizerCache.megaCache.clear() // :facepalm:
      textDocument
    }
  }

  private def getBuildTarget(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    val fromDatabase = tables.dependencySources.getBuildTarget(source)
    fromDatabase.orElse(inferBuildTarget(source))
  }

  private def inferBuildTarget(
      source: AbsolutePath
  ): Option[BuildTargetIdentifier] = {
    for {
      sourcesJarElement <- readonlyToSource.get(source).iterator
      elementUri = sourcesJarElement.toURI.toString
      uri = elementUri.stripPrefix("jar:").replaceFirst("!/.*", "")
      sourcesJar = AbsolutePath(Paths.get(URI.create(uri)))
      id <- buildTargets.inverseDependencySource(sourcesJar)
    } yield {
      tables.dependencySources.setBuildTarget(source, id)
      id
    }
  }.take(1).toList.headOption

  private def newGlobal(buildTarget: BuildTargetIdentifier): Option[Global] = {
    for {
      info <- buildTargets.info(buildTarget)
      scalaInfo <- info.asScalaBuildTarget
      if {
        val isOk = scalaInfo.getScalaVersion.startsWith("2.12")
        if (!isOk) reportUnsupportedScalaVersion(scalaInfo.getScalaVersion)
        isOk
      }
      scalacOptions <- buildTargets.scalacOptions(buildTarget)
    } yield newGlobal(scalacOptions)
  }

  private def reportUnsupportedScalaVersion(scalaVersion: String): Unit = {
    statusBar.addMessage(Only212Navigation.statusBar(scalaVersion))
    val notification = tables.dismissedNotifications.Only212Navigation
    if (!notification.isDismissed) {
      notification.dismiss(2, TimeUnit.MINUTES)
      client
        .showMessageRequest(Only212Navigation.params(scalaVersion))
        .asScala
        .foreach { item =>
          if (item == Only212Navigation.dismissForever) {
            notification.dismissForever()
          }
        }
    }
  }

  private def newGlobal(item: ScalacOptionsItem): Global = {
    val classpath = item.getClasspath.asScala.iterator
      .map(uri => Paths.get(URI.create(uri)))
      .filterNot(path => Files.isDirectory(path))
      .mkString(java.io.File.pathSeparator)
    val scalacOptions = item.getOptions.asScala.iterator
      .filterNot(_.isNonJVMPlatformOption)
      .toList
    InteractiveSemanticdb.newCompiler(classpath, scalacOptions)
  }

}

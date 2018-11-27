package scala.meta.internal.metals

import ch.epfl.scala.bsp4j
import java.util
import java.util.concurrent.ConcurrentLinkedQueue
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.services.LanguageClient
import scala.collection.concurrent.TrieMap
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

/**
 * Converts diagnostics from the build server into LSP diagnostics.
 *
 * This conversion is a bit tricky because BSP and LSP have different semantics
 * with how diagnostics are published:
 *
 * - BSP publishes diagnostics as they come from the compiler, to know what
 *   diagnostics are active for a document we may need to aggregate multiple
 *   `build/publishDiagnostics` notifications.
 * - LSP requires that the last `textDocument/publishDiagnostics` notification
 *   includes all active diagnostics for that document.
 */
final class Diagnostics(bsp: BuildTargets, languageClient: LanguageClient) {
  private val diagnostics =
    TrieMap.empty[AbsolutePath, util.Queue[Diagnostic]]

  def onBuildPublishDiagnostics(
      params: bsp4j.PublishDiagnosticsParams
  ): Unit = {
    val path = params.getTextDocument.getUri.toAbsolutePath
    val queue = diagnostics.getOrElseUpdate(
      path,
      new ConcurrentLinkedQueue[Diagnostic]()
    )
    if (params.getReset) {
      queue.clear()
    }
    params.getDiagnostics.forEach { buildDiagnostic =>
      queue.add(buildDiagnostic.toLSP)
    }
    // NOTE(olafur): it may be desirable to buffer up several diagnostics
    // before forwarding them to the editor client. With this implementation,
    // we risk publishing an exponential number diagnostics:
    // Step 1: [1]
    // Step 2: [1, 2]
    // Step 3: [1, 2, 3]
    // Step N: [1, ..., N]
    val uriString = params.getTextDocument.getUri
    val uri =
      if (uriString.startsWith("file:") && !uriString.startsWith("file:///")) {
        // Convert file:/foo/bar URIs to file:/// URIs in order to please clients
        // like vim-lsc that don't understand file:/foo/bar.
        // See https://github.com/scalacenter/bloop/issues/728
        // See https://microsoft.github.io/language-server-protocol/specification#uri
        uriString.toAbsolutePath.toURI.toString
      } else {
        uriString
      }
    languageClient.publishDiagnostics(
      new PublishDiagnosticsParams(uri, new util.ArrayList(queue))
    )
  }
}

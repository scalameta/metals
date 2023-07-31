package scala.meta.internal.decorations

import java.nio.charset.Charset

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final class InlayHintsProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffer: Buffers,
    client: MetalsLanguageClient,
    fingerprints: Md5Fingerprints,
    charset: Charset,
    focusedDocument: () => Option[AbsolutePath],
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    trees: Trees,
)(implicit ec: ExecutionContext)
    extends Decorations[l.InlayHint](
      workspace,
      semanticdbs,
      buffer,
      client,
      fingerprints,
      charset,
      focusedDocument,
      clientConfig,
      userConfig,
      trees,
    ) {

  override protected def toDecoration(
      lspRange: l.Range,
      decorationText: String,
  ): l.InlayHint = {
    // TODO add hover and command
    val hint = new l.InlayHint()

    // TODO might be parameter
    hint.setKind(l.InlayHintKind.Type)
    hint.setLabel(decorationText)
    hint.setPosition(lspRange.getEnd())
    hint
  }

  override protected def areSyntheticsEnabled: Boolean = {
    clientConfig.isInlayHintsEnabled() && super.areSyntheticsEnabled
  }

  def inlayHints(
      inlayHintsParams: l.InlayHintParams
  ): Future[Seq[l.InlayHint]] = Future {
    val path = inlayHintsParams.getTextDocument().getUri().toAbsolutePath
    syntheticDecorations(path, Option(inlayHintsParams.getRange()))
  }

  override def onChange(
      path: AbsolutePath,
      textDocument: TextDocument,
  ): Unit = ()

  override protected def refreshAction(doc: AbsolutePath): Future[Unit] = {
    if (clientConfig.isInlayHintsEnabled()) {
      client.refreshInlayHints().asScala.ignoreValue
    } else Future.unit
  }
}
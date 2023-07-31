package scala.meta.internal.decorations

import java.nio.charset.Charset

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.mtags.Md5Fingerprints
import scala.meta.internal.mtags.Semanticdbs
import scala.meta.internal.parsing.Trees
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

import org.eclipse.{lsp4j => l}

final class SyntheticsDecorationProvider(
    workspace: AbsolutePath,
    semanticdbs: Semanticdbs,
    buffer: Buffers,
    client: DecorationClient,
    fingerprints: Md5Fingerprints,
    charset: Charset,
    focusedDocument: () => Option[AbsolutePath],
    clientConfig: ClientConfiguration,
    userConfig: () => UserConfiguration,
    trees: Trees,
)(implicit ec: ExecutionContext)
    extends Decorations[DecorationOptions](
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

  override protected def areSyntheticsEnabled: Boolean =
    // !clientConfig.isInlayHintsEnabled() && 
    super.areSyntheticsEnabled

  override protected def toDecoration(
      lspRange: l.Range,
      decorationText: String,
  ): DecorationOptions = {
    // We don't add hover due to https://github.com/microsoft/vscode/issues/105302
    new DecorationOptions(
      lspRange,
      renderOptions = ThemableDecorationInstanceRenderOptions(
        after = ThemableDecorationAttachmentRenderOptions(
          decorationText,
          color = "grey",
          fontStyle = "italic",
          opacity = 0.7,
        )
      ),
    )

  }

  override def onChange(path: AbsolutePath, textDoc: TextDocument): Unit =
    publish(path, decorations(path, textDoc))

  /**
   * Publish synthetic decorations for path.
   * @param path path of the file to publish synthetic decorations for
   * @param isRefresh we don't want to send anything if all flags are disabled unless
   * it's a refresh, in which case we might want to remove decorations.
   */
  def publishSynthetics(
      path: AbsolutePath,
      isRefresh: Boolean = false,
  ): Future[Unit] = Future {
    if (isRefresh && !areSyntheticsEnabled) publish(path, Nil)
    else if (areSyntheticsEnabled) {
      val decorations = syntheticDecorations(path)
      publish(path, decorations)
    }
  }

  override protected def refreshAction(doc: AbsolutePath): Future[Unit] =
    publishSynthetics(doc, isRefresh = true)

  override def refresh(): Future[Unit] =
    focusedDocument() match {
      case Some(doc) => publishSynthetics(doc, isRefresh = true)
      case None => Future.unit
    }
  }

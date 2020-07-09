package scala.meta.internal.pc

import java.util
import java.util.Optional
import java.util.concurrent.TimeUnit

import scala.meta.internal.jdk.CollectionConverters._
import scala.meta.pc.PresentationCompilerConfig
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

case class PresentationCompilerConfigImpl(
    debug: Boolean = false,
    _parameterHintsCommand: Option[String] = None,
    _completionCommand: Option[String] = None,
    _symbolPrefixes: collection.Map[String, String] =
      PresentationCompilerConfig.defaultSymbolPrefixes().asScala,
    overrideDefFormat: OverrideDefFormat = OverrideDefFormat.Ascii,
    isCompletionItemDetailEnabled: Boolean = true,
    isCompletionItemDocumentationEnabled: Boolean = true,
    isHoverDocumentationEnabled: Boolean = true,
    snippetAutoIndent: Boolean = true,
    isFoldOnlyLines: Boolean = false,
    isSignatureHelpDocumentationEnabled: Boolean = true,
    isCompletionSnippetsEnabled: Boolean = true,
    isCompletionItemResolve: Boolean = true,
    isStripMarginOnTypeFormattingEnabled: Boolean = true,
    timeoutDelay: Long = 20,
    timeoutUnit: TimeUnit = TimeUnit.SECONDS
) extends PresentationCompilerConfig {
  override def symbolPrefixes(): util.Map[String, String] =
    _symbolPrefixes.asJava
  override def parameterHintsCommand: Optional[String] =
    Optional.ofNullable(_parameterHintsCommand.orNull)
  override def completionCommand: Optional[String] =
    Optional.ofNullable(_completionCommand.orNull)

  /**
   * Used to update the compiler config after we recieve the InitializationOptions.
   * If the user sets a value in the InitializationOptions, then the value will be
   * captures and set here. If not, we just resort back tot he default that already
   * exists.
   */
  def update(
      options: CompilerInitializationOptions
  ): PresentationCompilerConfigImpl =
    copy(
      isCompletionItemDetailEnabled = options.isCompletionItemDetailEnabled
        .fold(this.isCompletionItemDetailEnabled)(identity),
      isCompletionItemDocumentationEnabled =
        options.isCompletionItemDocumentationEnabled.fold(
          this.isCompletionItemDocumentationEnabled
        )(identity),
      isHoverDocumentationEnabled = options.isHoverDocumentationEnabled.fold(
        this.isHoverDocumentationEnabled
      )(identity),
      snippetAutoIndent =
        options.snippetAutoIndent.fold(this.snippetAutoIndent)(identity),
      isSignatureHelpDocumentationEnabled =
        options.isSignatureHelpDocumentationEnabled.fold(
          this.isSignatureHelpDocumentationEnabled
        )(identity),
      isCompletionItemResolve = options.isCompletionItemResolve.fold(
        this.isCompletionItemResolve
      )(identity)
    )
}

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
    timeoutDelay: Long = 20,
    timeoutUnit: TimeUnit = TimeUnit.SECONDS
) extends PresentationCompilerConfig {
  override def symbolPrefixes(): util.Map[String, String] =
    _symbolPrefixes.asJava
  override def parameterHintsCommand: Optional[String] =
    Optional.ofNullable(_parameterHintsCommand.orNull)
  override def completionCommand: Optional[String] =
    Optional.ofNullable(_completionCommand.orNull)

  def update(
      options: CompilerInitializationOptions
  ): PresentationCompilerConfigImpl =
    copy(
      isCompletionItemDetailEnabled =
        isCompletionItemDetailEnabled || options.isCompletionItemDetailEnabled,
      isCompletionItemDocumentationEnabled =
        isCompletionItemDocumentationEnabled || options.isCompletionItemDocumentationEnabled,
      isHoverDocumentationEnabled =
        isHoverDocumentationEnabled || options.isHoverDocumentationEnabled,
      snippetAutoIndent = snippetAutoIndent || options.snippetAutoIndent,
      isSignatureHelpDocumentationEnabled =
        isSignatureHelpDocumentationEnabled || options.isSignatureHelpDocumentationEnabled,
      isCompletionItemResolve =
        isCompletionItemResolve || options.isCompletionItemResolve
    )
}

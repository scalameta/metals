package scala.meta.internal.pc.telemetry

import scala.meta.pc
import scala.meta.internal.telemetry

import scala.jdk.CollectionConverters._

package object conversion {
  def PresentationCompilerConfig(
      config: pc.PresentationCompilerConfig
  ): telemetry.PresentationCompilerConfig =
    telemetry.PresentationCompilerConfig(
      symbolPrefixes = config.symbolPrefixes.asScala.toMap,
      overrideDefFormat = config.overrideDefFormat.name(),
      isDefaultSymbolPrefixes = config.isDefaultSymbolPrefixes,
      isCompletionItemDetailEnabled = config.isCompletionItemDetailEnabled,
      isStripMarginOnTypeFormattingEnabled =
        config.isStripMarginOnTypeFormattingEnabled(),
      isCompletionItemDocumentationEnabled =
        config.isCompletionItemDocumentationEnabled,
      isHoverDocumentationEnabled = config.isHoverDocumentationEnabled,
      snippetAutoIndent = config.snippetAutoIndent,
      isSignatureHelpDocumentationEnabled =
        config.isSignatureHelpDocumentationEnabled,
      isCompletionSnippetsEnabled = config.isCompletionItemDetailEnabled,
      semanticdbCompilerOptions =
        config.semanticdbCompilerOptions.asScala.toList
    )
}

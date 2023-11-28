package scala.meta.internal.pc.telemetry

import scala.meta.pc
import scala.meta.internal.telemetry
import java.util.Map
import java.util.List

// scalafmt: maxCollumn=100
package object conversion {
  def PresentationCompilerConfig(
      config: pc.PresentationCompilerConfig
  ): telemetry.PresentationCompilerConfig = new telemetry.PresentationCompilerConfig(
      /* symbolPrefixes = */ Map.copyOf(config.symbolPrefixes),
      /* completionCommand = */ config.completionCommand,
      /* parameterHintsCommand = */ config.parameterHintsCommand(),
      /* overrideDefFormat = */ config.overrideDefFormat.name(),
      /* isDefaultSymbolPrefixes = */ config.isDefaultSymbolPrefixes(),
      /* isCompletionItemDetailEnabled = */ config.isCompletionItemDetailEnabled(),
      /* isStripMarginOnTypeFormattingEnabled = */ config.isStripMarginOnTypeFormattingEnabled(),
      /* isCompletionItemDocumentationEnabled = */ config.isCompletionItemDocumentationEnabled(),
      /* isHoverDocumentationEnabled = */ config.isHoverDocumentationEnabled(),
      /* snippetAutoIndent = */ config.snippetAutoIndent(),
      /* isSignatureHelpDocumentationEnabled = */ config.isSignatureHelpDocumentationEnabled(),
      /* isCompletionSnippetsEnabled = */ config.isCompletionSnippetsEnabled(),
      /* semanticdbCompilerOptions = */ List.copyOf(config.semanticdbCompilerOptions)
    )
}

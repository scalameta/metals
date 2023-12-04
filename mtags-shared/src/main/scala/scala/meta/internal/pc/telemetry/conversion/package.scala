package scala.meta.internal.pc.telemetry

import java.{util => ju}

import scala.meta.internal.telemetry
import scala.meta.pc

// TOOD: scalafmt crashes when maxCollumn=100
package object conversion {
  def PresentationCompilerConfig(
      config: pc.PresentationCompilerConfig
  ): telemetry.PresentationCompilerConfig =
    new telemetry.PresentationCompilerConfig(
      /* symbolPrefixes = */ copyOf(config.symbolPrefixes),
      /* completionCommand = */ config.completionCommand,
      /* parameterHintsCommand = */ config.parameterHintsCommand(),
      /* overrideDefFormat = */ config.overrideDefFormat.name(),
      /* isDefaultSymbolPrefixes = */ config.isDefaultSymbolPrefixes(),
      /* isCompletionItemDetailEnabled = */ config
        .isCompletionItemDetailEnabled(),
      /* isStripMarginOnTypeFormattingEnabled = */ config
        .isStripMarginOnTypeFormattingEnabled(),
      /* isCompletionItemDocumentationEnabled = */ config
        .isCompletionItemDocumentationEnabled(),
      /* isHoverDocumentationEnabled = */ config.isHoverDocumentationEnabled(),
      /* snippetAutoIndent = */ config.snippetAutoIndent(),
      /* isSignatureHelpDocumentationEnabled = */ config
        .isSignatureHelpDocumentationEnabled(),
      /* isCompletionSnippetsEnabled = */ config.isCompletionSnippetsEnabled(),
      /* semanticdbCompilerOptions = */ copyOf(config.semanticdbCompilerOptions)
    )

  // Java Collections utilities not available in JDK 8
  private def copyOf[T](v: ju.List[T]): ju.List[T] = {
    val copy = new ju.ArrayList[T](v.size())
    copy.addAll(v)
    copy
  }

  private def copyOf[K, V](v: ju.Map[K, V]): ju.Map[K, V] = {
    val copy = new ju.HashMap[K, V](v.size())
    copy.putAll(v)
    copy
  }
}

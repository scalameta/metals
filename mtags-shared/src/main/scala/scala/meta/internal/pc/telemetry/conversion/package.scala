package scala.meta.internal.pc.telemetry

import java.{util => ju}

import scala.meta.internal.telemetry
import scala.meta.pc

package object conversion {
  def PresentationCompilerConfig(
      config: pc.PresentationCompilerConfig
  ): telemetry.PresentationCompilerConfig =
    new telemetry.PresentationCompilerConfig(
      copyOf(config.symbolPrefixes),
      config.completionCommand,
      config.parameterHintsCommand(),
      config.overrideDefFormat.name(),
      config.isDefaultSymbolPrefixes(),
      config.isCompletionItemDetailEnabled(),
      config.isStripMarginOnTypeFormattingEnabled(),
      config.isCompletionItemDocumentationEnabled(),
      config.isHoverDocumentationEnabled(),
      config.snippetAutoIndent(),
      config.isSignatureHelpDocumentationEnabled(),
      config.isCompletionSnippetsEnabled(),
      copyOf(config.semanticdbCompilerOptions)
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

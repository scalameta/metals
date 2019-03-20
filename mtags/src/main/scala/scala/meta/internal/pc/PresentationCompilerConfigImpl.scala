package scala.meta.internal.pc

import java.util
import java.util.Optional
import scala.meta.pc.PresentationCompilerConfig
import scala.collection.JavaConverters._
import scala.meta.pc.PresentationCompilerConfig.OverrideDefFormat

case class PresentationCompilerConfigImpl(
    debug: Boolean = false,
    _parameterHintsCommand: Option[String] = None,
    _symbolPrefixes: collection.Map[String, String] =
      PresentationCompilerConfig.defaultSymbolPrefixes().asScala,
    overrideDefFormat: OverrideDefFormat = OverrideDefFormat.Ascii,
    isCompletionItemDetailEnabled: Boolean = true,
    isCompletionItemDocumentationEnabled: Boolean = true,
    isHoverDocumentationEnabled: Boolean = true,
    isSignatureHelpDocumentationEnabled: Boolean = true
) extends PresentationCompilerConfig {
  override def symbolPrefixes(): util.Map[String, String] =
    _symbolPrefixes.asJava
  override def parameterHintsCommand: Optional[String] =
    Optional.ofNullable(_parameterHintsCommand.orNull)
}

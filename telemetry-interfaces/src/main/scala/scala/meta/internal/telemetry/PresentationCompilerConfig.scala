package scala.meta.internal.telemetry


case class PresentationCompilerConfig(
  symbolPrefixes: Map[String, String],
  completionCommand: Option[String],
  parameterHintsCommand: Option[String],
  overrideDefFormat: String,
  isDefaultSymbolPrefixes: Boolean,
  isCompletionItemDetailEnabled: Boolean,
  isStripMarginOnTypeFormattingEnabled: Boolean,
  isCompletionItemDocumentationEnabled: Boolean,
  isHoverDocumentationEnabled: Boolean,
  snippetAutoIndent: Boolean,
  isSignatureHelpDocumentationEnabled: Boolean,
  isCompletionSnippetsEnabled: Boolean,
  semanticdbCompilerOptions: List[String]
)

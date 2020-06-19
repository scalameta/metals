package scala.meta.internal.pc

case class CompilerInitializationOptions(
    isCompletionItemDetailEnabled: Boolean,
    isCompletionItemDocumentationEnabled: Boolean,
    isHoverDocumentationEnabled: Boolean,
    snippetAutoIndent: Boolean,
    isSignatureHelpDocumentationEnabled: Boolean,
    isCompletionItemResolve: Boolean
) {
  def this() =
    this(
      isCompletionItemDetailEnabled = true,
      isCompletionItemDocumentationEnabled = true,
      isHoverDocumentationEnabled = true,
      snippetAutoIndent = true,
      isSignatureHelpDocumentationEnabled = true,
      isCompletionItemResolve = true
    )
}

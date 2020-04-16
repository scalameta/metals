package scala.meta.internal.pc

case class CompilerInitializationOptions(
    isCompletionItemDetailEnabled: java.lang.Boolean = true,
    isCompletionItemDocumentationEnabled: java.lang.Boolean = true,
    isHoverDocumentationEnabled: Boolean = true,
    snippetAutoIndent: Boolean = true,
    isSignatureHelpDocumentationEnabled: Boolean = true
)

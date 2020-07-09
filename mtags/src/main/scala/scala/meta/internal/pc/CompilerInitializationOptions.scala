package scala.meta.internal.pc

/**
 * Represents the Compiler InitializationOptions that the client
 * sends in during the `initialize` process. If the the client doesn't
 * doesn't explicity send in anything, we default everything to None to
 * signify the client didn't set this. This will then default to what is
 * currently set (more than likely the defaults) in the PresentationCompilerConfigImpl.
 */
case class CompilerInitializationOptions(
    isCompletionItemDetailEnabled: Option[Boolean],
    isCompletionItemDocumentationEnabled: Option[Boolean],
    isHoverDocumentationEnabled: Option[Boolean],
    snippetAutoIndent: Option[Boolean],
    isSignatureHelpDocumentationEnabled: Option[Boolean],
    isCompletionItemResolve: Option[Boolean]
)

object CompilerInitializationOptions {
  def default: CompilerInitializationOptions =
    CompilerInitializationOptions(None, None, None, None, None, None)
}

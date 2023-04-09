package scala.meta.internal.pc

/**
 * Represents the Compiler InitializationOptions that the client
 * sends in during the `initialize` process. If the the client doesn't
 * doesn't explicity send in anything, we default everything to None to
 * signify the client didn't set this. This will then default to what is
 * currently set (more than likely the defaults) in the PresentationCompilerConfigImpl.
 *
 * @param completionCommand command identifier to trigger completion.
 * @param isCompletionItemDetailEnabled whether the `CompletionItem.detail` field should be
 *                                      populated.
 * @param isCompletionItemDocumentationEnabled whether the `CompletionItem.documentation`
 *                                             field should be populated.
 * @param isCompletionItemResolve whether the client wants Metals to handle `completionItem/resolve`
 * @param isHoverDocumentationEnabled whether to include docstrings in a `textDocument/hover`.
 * @param isSignatureHelpDocumentationEnabled whether the `SignatureHelp.documentation` field
 *                                            should be populated.
 * @param overrideDefFormat whether the override should include a unicode icon or only ascii.
 * @param parameterHintsCommand command identifier to trigger parameter hints.
 * @param snippetAutoIndent whether the client defaults to adding the indentation of the reference
 *                          line that the operation started on.
 */
case class CompilerInitializationOptions(
    completionCommand: Option[String],
    isCompletionItemDetailEnabled: Option[Boolean],
    isCompletionItemDocumentationEnabled: Option[Boolean],
    isCompletionItemResolve: Option[Boolean],
    isHoverDocumentationEnabled: Option[Boolean],
    isSignatureHelpDocumentationEnabled: Option[Boolean],
    overrideDefFormat: Option[String],
    parameterHintsCommand: Option[String],
    snippetAutoIndent: Option[Boolean]
)

object CompilerInitializationOptions {
  def default: CompilerInitializationOptions =
    CompilerInitializationOptions(
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None
    )
}

package scala.meta.internal.metals.debug

import org.eclipse.lsp4j.debug as dap

final case class StackFrame(
    threadId: Long,
    info: dap.StackFrame,
    variables: Variables,
)

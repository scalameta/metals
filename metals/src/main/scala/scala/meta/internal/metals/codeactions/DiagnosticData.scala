package scala.meta.internal.metals.codeactions

import java.{util => ju}

import org.eclipse.lsp4j.WorkspaceEdit

/**
 * Representation of what should be in the `data` field of a BSP diagnostic.
 *
 * NOTE: In the future there may be other keys besides `edits`, but we can
 * probably just cross that bridge when we get there.
 *
 * @param edits The various edits that can be associated with a given Diagnostic.
 */
final case class DiagnosticData(edits: ju.List[WorkspaceEdit])

package scala.meta.internal.mtags

import dotty.tools.dotc.util.SourcePosition
import org.eclipse.{lsp4j => l}

object MtagsEnrichments
    extends CommonMtagsEnrichments
    with VersionSpecificEnrichments {

  extension (pos: SourcePosition)
    def toLSP: l.Range = {
      new l.Range(
        new l.Position(pos.startLine, pos.startColumn),
        new l.Position(pos.endLine, pos.endColumn)
      )
    }
}

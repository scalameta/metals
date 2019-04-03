package scala.meta.internal.metals

import org.eclipse.lsp4j.FoldingRange

trait FoldingMode {
  def adjust(range: FoldingRange): Unit
}

object FoldingMode {
  object FoldLines extends FoldingMode {
    override def adjust(range: FoldingRange): Unit = preserveLastLine(range)

    private def preserveLastLine(range: FoldingRange): Unit = {
      val adjustedEndLine = range.getEndLine - 1 // we want to preserve the last line containing e.g. '}'
      range.setEndLine(adjustedEndLine)
    }
  }

  object FoldCharacters extends FoldingMode {
    override def adjust(range: FoldingRange): Unit = {}
  }
}

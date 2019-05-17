package tests

import org.eclipse.{lsp4j => l}

object CodeLensesTextEdits {
  def apply(ranges: Seq[l.CodeLens]): List[l.TextEdit] = {
    ranges.map(convert).toList
  }

  private def convert(lens: l.CodeLens): l.TextEdit = {
    val range = lens.getRange
    val pos = new l.Position(range.getStart.getLine, 0)
    new l.TextEdit(new l.Range(pos, pos), print(lens))
  }

  private def print(lens: l.CodeLens): String = {
    val command = lens.getCommand
    s"<<${command.getTitle}>>\n"
  }
}

package tests

import org.eclipse.{lsp4j => l}

object CodeLensesTextEdits {
  def apply(lenses: Seq[l.CodeLens]): Iterable[l.TextEdit] = {
    for {
      (line, lenses) <- lenses.groupBy(_.getRange.getStart.getLine)
      textEdit = convert(line, lenses)
    } yield textEdit
  }

  private def convert(line: Int, lenses: Seq[l.CodeLens]): l.TextEdit = {
    val pos = new l.Position(line, 0)
    val edit = lenses.map(print).mkString
    new l.TextEdit(new l.Range(pos, pos), edit + System.lineSeparator())
  }

  private def print(lens: l.CodeLens): String = {
    val command = lens.getCommand
    s"<<${command.getTitle}>>"
  }
}

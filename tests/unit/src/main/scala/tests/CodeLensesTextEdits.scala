package tests

import scala.jdk.CollectionConverters._

import org.eclipse.{lsp4j => l}

object CodeLensesTextEdits {
  def apply(
      lenses: Seq[l.CodeLens],
      printCommand: Boolean
  ): Iterable[l.TextEdit] = {
    def convert(line: Int, lenses: Seq[l.CodeLens]): l.TextEdit = {
      val pos = new l.Position(line, 0)
      val edit = lenses.map(print).mkString
      new l.TextEdit(new l.Range(pos, pos), edit + System.lineSeparator())
    }

    def printArgument(arg: AnyRef) = {
      arg match {
        case loc: l.Location =>
          val start = loc.getRange().getStart()
          s"${loc.getUri()}:${start.getLine()}:${start.getCharacter()}"
        case other => other.toString()
      }
    }

    def print(lens: l.CodeLens): String = {
      val command = lens.getCommand
      if (printCommand) {
        val arguments = command
          .getArguments()
          .asScala
          .map(printArgument)
          .mkString("[", ", ", "]")
        s"<<${command.getTitle()} ${command.getCommand()}$arguments>>"
      } else
        s"<<${command.getTitle}>>"
    }

    for {
      (line, lenses) <- lenses.groupBy(_.getRange.getStart.getLine)
      textEdit = convert(line, lenses)
    } yield textEdit
  }

}

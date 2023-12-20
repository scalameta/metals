package tests

import scala.meta.internal.inputs.XtensionInputSyntaxStructure

import munit.internal.difflib.Diffs

case class WorkspaceSymbolReferences(
    references: Seq[SymbolReference],
    definition: Seq[SymbolReference],
) {
  def format(locations: Seq[SymbolReference]): String =
    locations
      .groupBy(_.symbol)
      .toSeq
      .sortBy { case (symbol, _) =>
        symbol
      }
      .flatMap { case (symbol, refs) =>
        val header = "=" * (symbol.length + 2)
        Seq(
          header,
          "= " + symbol,
          header,
        ) ++ refs
          .sortBy(l => (l.pos.input.syntax, l.pos.start))
          .map(_.format)
      }
      .mkString("\n")
  def referencesFormat: String = format(references)
  def definitionFormat: String = format(definition)
  def diff: String = Diffs.unifiedDiff(referencesFormat, definitionFormat)

}

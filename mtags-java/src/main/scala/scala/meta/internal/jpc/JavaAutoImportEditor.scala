package scala.meta.internal.jpc

import org.eclipse.{lsp4j => l}

/**
 * Shared class to manage the insertion of Java auto-imports.
 *
 * @param text Source code of the Java file.
 * @param fqn The fully qualified name of the class to import. Example "java.util.List".
 */
class JavaAutoImportEditor(text: String, fqn: String) {

  def textEdit(): l.TextEdit = {
    closestImport()
      .orElse(packageLine())
      .getOrElse(firstLineImport())
  }

  // There's an existing package line but no imports so we place the import
  // after the package line with a blank line between.
  private def packageLine(): Option[l.TextEdit] = {
    val candidates = for {
      (line, lineNumber) <- text.linesIterator.zipWithIndex
      if line.startsWith("package ")
    } yield new l.TextEdit(
      new l.Range(
        new l.Position(lineNumber, line.length()),
        new l.Position(lineNumber, line.length())
      ),
      s"\n\nimport $fqn;\n"
    )

    // Scala supports multiple package lines, while Java only supports one so we
    // assume there's only one package line.
    if (candidates.hasNext) {
      Some(candidates.next())
    } else {
      None
    }
  }

  // There's no existing package or import line so we place the import at the
  // top of the file followed by a blank line.
  private def firstLineImport(): l.TextEdit = new l.TextEdit(
    new l.Range(
      new l.Position(0, 0),
      new l.Position(0, 0)
    ),
    s"import $fqn;\n\n"
  )

  // The file has imports, so we place the auto-import below/above the closest
  // match (the import with the longest shared prefix with the fqn).
  private def closestImport(): Option[l.TextEdit] = {
    val candidates = ImportLine.fromText(text)
    if (candidates.isEmpty) {
      return None
    }

    val candidate = candidates.maxBy(c => c.importPrefixMatchLength(fqn))
    // Place the import before this line if the candidate is greater than the fqn

    val (character, insertText) =
      if (candidate.isGreater(fqn)) {
        (0, s"import $fqn;\n")
      } else {
        (candidate.line.length(), s"\nimport $fqn;")
      }
    val edit = new l.TextEdit(
      new l.Range(
        new l.Position(candidate.lineNumber, character),
        new l.Position(candidate.lineNumber, character)
      ),
      insertText
    )
    Some(edit)
  }

}

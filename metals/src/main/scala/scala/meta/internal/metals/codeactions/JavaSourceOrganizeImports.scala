package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.pc.CancelToken

import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.ImportOrderer
import com.google.googlejavaformat.java.RemoveUnusedImports
import org.eclipse.{lsp4j => l}

/**
 * Removes unused imports and sorts the remaining ones in a Java file without
 * formatting anything else.
 */
class JavaSourceOrganizeImports(buffers: Buffers) extends CodeAction {
  import JavaSourceOrganizeImports._

  override val kind: String = JavaSourceOrganizeImports.kind
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument().getUri().toAbsolutePath
    // Organizing imports parses the whole file, so only do it when explicitly requested.
    val isRequested = Option(params.getContext().getOnly()).exists(!_.isEmpty())
    if (!isRequested) Nil
    else
      for {
        text <- buffers.get(path).orElse(path.readTextOpt).toSeq
        organized <- organizeImports(text).toSeq
        edit <- minimalEdit(text, organized).toSeq
      } yield CodeActionBuilder.build(
        title,
        kind,
        changes = Seq(path -> Seq(edit)),
      )
  }
}

object JavaSourceOrganizeImports {
  final val kind: String = l.CodeActionKind.SourceOrganizeImports
  final val title: String = "Organize imports"

  private def organizeImports(text: String): Option[String] =
    try {
      Some(
        ImportOrderer.reorderImports(
          RemoveUnusedImports.removeUnusedImports(text)
        )
      )
    } catch {
      // Syntax errors, the file can't be organized
      case _: FormatterException => None
      // Missing --add-exports for javac internals
      case _: LinkageError => None
      case NonFatal(e) =>
        scribe.warn("Failed to organize Java imports", e)
        None
    }

  /**
   * Replaces only the lines that differ so that the edit doesn't touch the rest of the file.
   */
  private[codeactions] def minimalEdit(
      original: String,
      organized: String,
  ): Option[l.TextEdit] =
    if (original == organized) None
    else {
      val oldLines = original.split("\n", -1)
      val newLines = organized.split("\n", -1)
      val prefix = oldLines
        .zip(newLines)
        .takeWhile { case (o, n) =>
          o == n
        }
        .length
      // the suffix must not overlap with the common prefix
      val maxSuffix = math.min(oldLines.length, newLines.length) - prefix
      val suffix = oldLines.reverse
        .zip(newLines.reverse)
        .takeWhile { case (o, n) => o == n }
        .length
        .min(maxSuffix)
      val changed = newLines.slice(prefix, newLines.length - suffix)
      if (suffix == 0) {
        // The change reaches the last line, which has no trailing newline.
        val lastLine = oldLines.length - 1
        Some(
          new l.TextEdit(
            new l.Range(
              new l.Position(prefix, 0),
              new l.Position(lastLine, oldLines(lastLine).length()),
            ),
            changed.mkString("\n"),
          )
        )
      } else {
        Some(
          new l.TextEdit(
            new l.Range(
              new l.Position(prefix, 0),
              new l.Position(oldLines.length - suffix, 0),
            ),
            changed.map(_ + "\n").mkString,
          )
        )
      }
    }
}

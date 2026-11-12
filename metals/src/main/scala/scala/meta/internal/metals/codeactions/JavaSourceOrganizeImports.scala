package scala.meta.internal.metals.codeactions

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.Buffers
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.pc.CancelToken
import scala.meta.{inputs => m}

import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.ImportOrderer
import com.google.googlejavaformat.java.JavaFormatterOptions.Style
import com.google.googlejavaformat.java.RemoveUnusedImports
import org.eclipse.{lsp4j => l}

/**
 * Removes unused imports and sorts the remaining ones in a Java file without
 * formatting anything else.
 */
class JavaSourceOrganizeImports(buffers: Buffers) extends CodeAction {
  import JavaSourceOrganizeImports.*

  override val kind: String = JavaSourceOrganizeImports.kind
  override def isScala: Boolean = false
  override def isJava: Boolean = true

  override def contribute(
      params: l.CodeActionParams,
      token: CancelToken,
  )(implicit ec: ExecutionContext): Future[Seq[l.CodeAction]] = Future {
    val path = params.getTextDocument.getUri.toAbsolutePath
    // Organizing imports parses the whole file, so only do it when explicitly requested.
    val isRequested = Option(params.getContext.getOnly).exists(!_.isEmpty)
    if (!isRequested) Nil
    else
      for {
        text <- buffers.get(path).orElse(path.readTextOpt).toSeq
        organized <- organizeImports(text).toSeq
        edit <- minimalEdit(path, text, organized).toSeq
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
          RemoveUnusedImports.removeUnusedImports(text),
          Style.GOOGLE,
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
   * Replaces only the part of the text that differs, so that the edit doesn't touch the rest of the file.
   */
  private[codeactions] def minimalEdit(
      path: AbsolutePath,
      original: String,
      organized: String,
  ): Option[l.TextEdit] = Option.unless(original == organized) {
    val prefix = original
      .zip(organized)
      .takeWhile { case (o, n) => o == n }
      .length
    val maxSuffix = math.min(original.length, organized.length) - prefix
    val suffix = original.reverse
      .zip(organized.reverse)
      .takeWhile { case (o, n) => o == n }
      .length
      .min(maxSuffix)
    val input = m.Input.VirtualFile(path.toURI.toString, original)
    val range =
      m.Position.Range(input, prefix, original.length - suffix).toLsp
    new l.TextEdit(
      range,
      organized.substring(prefix, organized.length - suffix),
    )
  }
}

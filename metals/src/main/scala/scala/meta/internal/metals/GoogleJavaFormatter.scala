package scala.meta.internal.metals

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.{inputs => m}

import com.google.googlejavaformat.java.Formatter
import com.google.googlejavaformat.java.FormatterException
import com.google.googlejavaformat.java.ImportOrderer
import com.google.googlejavaformat.java.JavaFormatterOptions
import com.google.googlejavaformat.java.JavaFormatterOptions.Style
import com.google.googlejavaformat.java.RemoveUnusedImports
import org.eclipse.{lsp4j => l}

final class GoogleJavaFormatter(client: () => l.services.LanguageClient) {
  def format(input: m.Input.VirtualFile): List[l.TextEdit] = {
    val options = JavaFormatterOptions
      .builder()
      .style(Style.GOOGLE)
      .build()
    val formatter = new Formatter(options)

    try {
      val withOrganizedImports = ImportOrderer.reorderImports(
        RemoveUnusedImports.removeUnusedImports(input.text)
      )
      val formatted = formatter.formatSource(withOrganizedImports)
      if (formatted != input.text) {
        val fullDocumentRange = fromLSP(input).toLsp
        List(new l.TextEdit(fullDocumentRange, formatted))
      } else {
        Nil
      }
    } catch {
      case _: IllegalAccessError =>
        client().showMessage(
          new l.MessageParams(
            l.MessageType.Error,
            """|Unable to format with Google Java Format due to missing VM options. To fix this problem, add the following settings
               |
               |"metals.serverProperties": [
               |  "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
               |  "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
               |  "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
               |  "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
               |  "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
               |  "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
               |]
               |
               |Alternatively, add the setting "metals.javaFormatter": "eclipse" | "none"
               |""".stripMargin,
          )
        )
        Nil
      case e: FormatterException =>
        scribe.error(s"${input.path}:${e.getMessage()}")
        Nil
      case NonFatal(e) =>
        scribe.error("Failed to format with Google Java Format", e)
        Nil
    }
  }

  private def fromLSP(input: m.Input): m.Position.Range =
    m.Position.Range(input, 0, input.chars.length)
}

package tests

import org.langmeta.semanticdb.Document

import java.nio.file.Files
import org.langmeta.io.AbsolutePath
import scala.meta.interactive.InteractiveSemanticdb
import scala.meta.metals.Uri
import scala.meta.metals.compiler.{Cursor, ScalacProvider}
import scala.tools.nsc.interactive.Global

class CompilerSuite extends MegaSuite {
  val compiler: Global = ScalacProvider.newCompiler(
    "",
    "-deprecation" ::
      "-Ywarn-unused-import" ::
      Nil
  )

  val sourceDirectory = AbsolutePath(Files.createTempDirectory("metals"))

  def toDocument(name: String, code: String): Document = {
    InteractiveSemanticdb.toDocument(compiler, code, name, 10000)
  }

  private def computeChevronPositionFromMarkup(
      filename: String,
      markup: String
  ): Cursor = {
    val chevrons = "<<(.*?)>>".r
    val carets0 =
      chevrons.findAllIn(markup).matchData.map(m => (m.start, m.end)).toList
    val carets = carets0.zipWithIndex.map {
      case ((s, e), i) => (s - 4 * i, e - 4 * i - 4)
    }
    carets match {
      case (start, end) :: Nil =>
        val code = chevrons.replaceAllIn(markup, "$1")
        val uri = Uri(sourceDirectory.resolve(filename))
        Cursor(uri, code, start)
      case els =>
        throw new IllegalArgumentException(
          s"Expected one chevron, found ${els.length}"
        )
    }
  }

  /**
   * Utility to test the presentation compiler with a position.
   *
   * Use it like like this:
   * {{{
   *   targeted(
   *     "apply",
   *     "object Main { Lis<<t>>", { arg =>
   *       assert(compiler.typeCompletionsAt(arg) == "List" :: Nil)
   *     }
   *   )
   * }}}
   * The `<<t>>` chevron indicates the callback position.
   *
   * See SignatureHelpTest for more inspiration on how to abstract further on
   * top of this method.
   */
  def targeted(
      filename: String,
      markup: String,
      fn: Cursor => Unit
  ): Unit = {
    test(filename.replace(' ', '-')) {
      val point =
        computeChevronPositionFromMarkup(filename + ".scala", markup)
      fn(point)
    }
  }

}

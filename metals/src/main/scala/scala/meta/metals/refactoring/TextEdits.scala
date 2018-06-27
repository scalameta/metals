package scala.meta.metals.refactoring

import scala.meta.Input
import org.langmeta.languageserver.InputEnrichments._
import scala.annotation.tailrec
import scala.meta.metals.ScalametaEnrichments._
import scala.meta.lsp.TextEdit

/** Re-implementation of how TextEdits should be handled by the editor client
 *
 * Useful for testing/logging purposes. Instead of reading massive JSON blobs like
 * [{"range":{"start":{"line":1,"character":0},"end":{"line":2,"character":0}},"newText":""},
 *  ...]
 * you can look at how the code looks like applied instead.
 */
object TextEdits {
  def applyToInput(
      input: Input,
      edits: List[TextEdit]
  ): String = {
    val original = input.contents
    val sb = new java.lang.StringBuilder
    @tailrec def loop(i: Int, es: List[TextEdit]): Unit = {
      val isDone = i >= original.length
      if (isDone) ()
      else {
        es match {
          case Nil =>
            sb.append(original.substring(i))
          case edit :: tail =>
            val pos = input.toPosition(edit.range)
            sb.append(original.substring(i, pos.start))
              .append(edit.newText)
            loop(pos.end, tail)
        }
      }
    }
    loop(0, edits)
    sb.toString
  }
}

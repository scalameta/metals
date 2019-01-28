package bench

import java.nio.charset.StandardCharsets
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.pc.CompletionItems
import scala.meta.pc.PresentationCompiler

case class SourceCompletion(filename: String, code: String, offset: Int) {
  def complete(pc: PresentationCompiler): CompletionItems =
    pc.complete(CompilerOffsetParams(filename, code, offset))
}

object SourceCompletion {
  def fromPath(path: String, query: String): SourceCompletion = {
    val text = readResource(path)
    val queryIndex = text.indexOf(query.replaceAllLiterally("@@", ""))
    if (queryIndex < 0) throw new IllegalArgumentException(query)
    val offset = query.indexOf("@@")
    if (offset < 0) throw new IllegalArgumentException(query)
    SourceCompletion(path, text, queryIndex + offset)
  }
  private def readResource(path: String): String =
    new String(
      InputStreamIO.readBytes(
        this.getClass.getResourceAsStream(s"/$path")
      ),
      StandardCharsets.UTF_8
    )
}

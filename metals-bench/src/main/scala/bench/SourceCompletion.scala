package bench

import java.nio.charset.StandardCharsets
import scala.meta.internal.io.FileIO
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.io.AbsolutePath
import scala.meta.pc.CompletionItems
import scala.meta.pc.PresentationCompiler
import scala.util.Random

case class SourceCompletion(filename: String, code: String, offset: Int) {
  def complete(pc: PresentationCompiler): CompletionItems = {
    // Trigger re-typechecking
    val randomSuffix = s"\n/* ${Random.nextInt()} */\n"
    pc.complete(CompilerOffsetParams(filename, code + randomSuffix, offset))
  }
}

object SourceCompletion {
  def fromZipPath(
      akka: AbsolutePath,
      path: String,
      query: String
  ): SourceCompletion = {
    val text = FileIO.withJarFileSystem(akka, create = false, close = true)(
      root => FileIO.slurp(root.resolve(path), StandardCharsets.UTF_8)
    )
    fromPath(path, text, query)
  }
  def fromResourcePath(path: String, query: String): SourceCompletion = {
    fromPath(path, readResource(path), query)
  }
  def fromPath(path: String, text: String, query: String): SourceCompletion = {
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

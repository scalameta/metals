package bench

import java.nio.charset.StandardCharsets
import java.nio.file.Paths
import java.util.Optional
import java.{util => ju}

import scala.util.Random

import scala.meta.internal.io.FileIO
import scala.meta.internal.io.InputStreamIO
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.io.AbsolutePath
import scala.meta.pc.DefinitionResult
import scala.meta.pc.HoverSignature
import scala.meta.pc.PresentationCompiler

import org.eclipse.lsp4j.CompletionList
import org.eclipse.lsp4j.DocumentHighlight
import org.eclipse.lsp4j.SignatureHelp

/**
 * A helper to create a benchmark for completions given a source file and offset.
 */
case class SourceRequest(filename: String, code: String, offset: Int) {
  // Trigger re-typechecking
  private def randomSuffix = s"\n/* ${Random.nextInt()} */\n"

  def complete(pc: PresentationCompiler): CompletionList = {
    pc.complete(
      CompilerOffsetParams(
        Paths.get(filename).toUri(),
        code + randomSuffix,
        offset,
      )
    ).get()
  }

  def hover(pc: PresentationCompiler): Optional[HoverSignature] = {
    pc.hover(
      CompilerOffsetParams(
        Paths.get(filename).toUri(),
        code + randomSuffix,
        offset,
      )
    ).get()
  }

  def signatureHelp(pc: PresentationCompiler): SignatureHelp = {
    pc.signatureHelp(
      CompilerOffsetParams(
        Paths.get(filename).toUri(),
        code + randomSuffix,
        offset,
      )
    ).get()
  }

  def documentHighlight(
      pc: PresentationCompiler
  ): ju.List[DocumentHighlight] = {
    pc.documentHighlight(
      CompilerOffsetParams(
        Paths.get(filename).toUri(),
        code + randomSuffix,
        offset,
      )
    ).get()
  }
  def definition(pc: PresentationCompiler): DefinitionResult = {
    pc.definition(
      CompilerOffsetParams(
        Paths.get(filename).toUri(),
        code + randomSuffix,
        offset,
      )
    ).get()
  }
}

object SourceRequest {
  def fromZipPath(
      zip: AbsolutePath,
      path: String,
      query: String,
  ): SourceRequest = {
    val text =
      FileIO.withJarFileSystem(zip, create = false, close = true)(root =>
        FileIO.slurp(root.resolve(path), StandardCharsets.UTF_8)
      )
    fromPath(path, text, query)
  }
  def fromResourcePath(path: String, query: String): SourceRequest = {
    fromPath(path, readResource(path), query)
  }
  def fromPath(path: String, text: String, query: String): SourceRequest = {
    val queryIndex = text.indexOf(query.replace("@@", ""))
    if (queryIndex < 0) throw new IllegalArgumentException(query)
    val offset = query.indexOf("@@")
    if (offset < 0) throw new IllegalArgumentException(query)
    SourceRequest(path, text, queryIndex + offset)
  }
  private def readResource(path: String): String =
    new String(
      InputStreamIO.readBytes(
        this.getClass.getResourceAsStream(s"/$path")
      ),
      StandardCharsets.UTF_8,
    )
}

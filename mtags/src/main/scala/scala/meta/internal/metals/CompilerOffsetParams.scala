package scala.meta.internal.metals

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Paths

import scala.meta.inputs.Position
import scala.meta.internal.inputs.XtensionInputSyntaxStructure
import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams

case class CompilerOffsetParams(
    uri: URI,
    text: String,
    offset: Int,
    token: CancelToken = EmptyCancelToken
) extends OffsetParams

object CompilerOffsetParams {

  def fromPos(pos: Position, token: CancelToken): CompilerOffsetParams = {
    val syntax = pos.input.syntax
    val uri =
      try {
        val uri = URI.create(syntax)
        Paths.get(uri)
        uri
      } catch {
        case _: IllegalArgumentException | _: URISyntaxException =>
          Paths.get(syntax).toUri
      }
    CompilerOffsetParams(
      uri,
      pos.input.text,
      pos.start,
      token
    )
  }
}

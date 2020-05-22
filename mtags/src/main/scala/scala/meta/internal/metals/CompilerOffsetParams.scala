package scala.meta.internal.metals

import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.inputs.Position
import meta.internal.inputs.XtensionInputSyntaxStructure
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Paths

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
      try new URI(syntax)
      catch {
        case _: URISyntaxException =>
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

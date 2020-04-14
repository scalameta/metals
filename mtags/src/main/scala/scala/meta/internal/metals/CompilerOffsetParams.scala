package scala.meta.internal.metals

import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.inputs.Position
import meta.internal.inputs.XtensionInputSyntaxStructure
import java.net.URI

case class CompilerOffsetParams(
    uri: URI,
    text: String,
    offset: Int,
    token: CancelToken = EmptyCancelToken
) extends OffsetParams

object CompilerOffsetParams {

  def fromPos(pos: Position, token: CancelToken): CompilerOffsetParams = {
    CompilerOffsetParams(
      URI.create(pos.input.syntax),
      pos.input.text,
      pos.start,
      token
    )
  }
}

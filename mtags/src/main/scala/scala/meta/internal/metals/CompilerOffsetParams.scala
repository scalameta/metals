package scala.meta.internal.metals

import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.inputs.Position

case class CompilerOffsetParams(
    filename: String,
    text: String,
    offset: Int,
    token: CancelToken = EmptyCancelToken
) extends OffsetParams

object CompilerOffsetParams {
  def fromPos(pos: Position, token: CancelToken): CompilerOffsetParams = {
    CompilerOffsetParams(
      pos.input.syntax,
      pos.input.text,
      pos.start,
      token
    )
  }
}

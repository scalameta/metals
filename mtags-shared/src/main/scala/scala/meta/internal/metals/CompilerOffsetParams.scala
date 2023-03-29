package scala.meta.internal.metals

import java.net.URI

import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

case class CompilerOffsetParams(
    uri: URI,
    text: String,
    offset: Int,
    token: CancelToken = EmptyCancelToken
) extends OffsetParams

case class CompilerRangeParams(
    uri: URI,
    text: String,
    offset: Int,
    endOffset: Int,
    token: CancelToken = EmptyCancelToken
) extends RangeParams {
  def toCompilerOffsetParams: CompilerOffsetParams =
    CompilerOffsetParams(
      uri,
      text,
      offset,
      token
    )
}

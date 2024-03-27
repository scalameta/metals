package scala.meta.internal.metals

import java.net.URI
import java.util.Optional

import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.pc.OutlineFiles
import scala.meta.pc.RangeParams

case class CompilerOffsetParams(
    uri: URI,
    text: String,
    offset: Int,
    token: CancelToken,
    override val outlineFiles: Optional[OutlineFiles]
) extends OffsetParams

object CompilerOffsetParams {
  def apply(
      uri: URI,
      text: String,
      offset: Int,
      token: CancelToken = EmptyCancelToken
  ): CompilerOffsetParams =
    CompilerOffsetParams(uri, text, offset, token, Optional.empty())
}

case class CompilerRangeParams(
    uri: URI,
    text: String,
    offset: Int,
    endOffset: Int,
    token: CancelToken,
    override val outlineFiles: Optional[OutlineFiles]
) extends RangeParams {

  def toCompilerOffsetParams: CompilerOffsetParams =
    CompilerOffsetParams(
      uri,
      text,
      offset,
      token,
      outlineFiles
    )
}

object CompilerRangeParams {
  def apply(
      uri: URI,
      text: String,
      offset: Int,
      endOffset: Int,
      token: CancelToken = EmptyCancelToken
  ): CompilerRangeParams =
    CompilerRangeParams(uri, text, offset, endOffset, token, Optional.empty())
}

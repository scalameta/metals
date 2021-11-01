package scala.meta.internal.metals

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Paths

import scala.meta.inputs.Position
import scala.meta.internal.inputs.XtensionInputSyntaxStructure
import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.pc.RangeParams

trait OffsetParamsUtils {
  protected def syntaxURI(pos: Position): URI = {
    val syntax = pos.input.syntax
    try {
      val uri = URI.create(syntax)
      Paths.get(uri)
      uri
    } catch {
      case _: IllegalArgumentException | _: URISyntaxException =>
        Paths.get(syntax).toUri
    }
  }
}

case class CompilerOffsetParams(
    uri: URI,
    text: String,
    offset: Int,
    token: CancelToken = EmptyCancelToken
) extends OffsetParams

object CompilerOffsetParams extends OffsetParamsUtils {

  def fromPos(pos: Position, token: CancelToken): CompilerOffsetParams = {
    val uri = syntaxURI(pos)
    CompilerOffsetParams(
      uri,
      pos.input.text,
      pos.start,
      token
    )
  }
}

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

object CompilerRangeParams extends OffsetParamsUtils {

  def offsetOrRange(pos: Position, token: CancelToken): OffsetParams = {
    if (pos.start == pos.end)
      CompilerOffsetParams.fromPos(pos, token)
    else
      CompilerRangeParams.fromPos(pos, token)
  }

  def fromPos(pos: Position, token: CancelToken): CompilerRangeParams = {
    val uri = syntaxURI(pos)
    CompilerRangeParams(
      uri,
      pos.input.text,
      pos.start,
      pos.end,
      token
    )
  }
}

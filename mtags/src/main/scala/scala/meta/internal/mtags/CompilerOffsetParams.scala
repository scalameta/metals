package scala.meta.internal.mtags

import scala.meta.inputs.Position
import java.net.URI
import java.nio.file.Paths
import java.net.URISyntaxException
import scala.meta.pc.{CancelToken, OffsetParams, RangeParams}
import meta.internal.inputs.XtensionInputSyntaxStructure

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

object CompilerRangeParamsUtils extends OffsetParamsUtils {

  def offsetOrRange(pos: Position, token: CancelToken): OffsetParams = {
    if (pos.start == pos.end)
      CompilerOffsetParamsUtils.fromPos(pos, token)
    else
      CompilerRangeParamsUtils.fromPos(pos, token)
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

object CompilerOffsetParamsUtils extends OffsetParamsUtils {

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

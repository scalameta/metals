package scala.meta.internal.mtags

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Paths

import scala.meta.inputs.Position
import scala.meta.internal.inputs.XtensionInputSyntaxStructure
import scala.meta.internal.metals.CompilerOffsetParams
import scala.meta.internal.metals.CompilerRangeParams
import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams

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

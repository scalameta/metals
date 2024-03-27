package scala.meta.internal.metals

import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Paths
import java.util.Optional

import scala.meta.inputs.Position
import scala.meta.internal.inputs.XtensionInputSyntaxStructure
import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams
import scala.meta.pc.OutlineFiles

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

  def offsetOrRange(
      pos: Position,
      token: CancelToken,
      outlineFiles: Optional[OutlineFiles] = Optional.empty()
  ): OffsetParams = {
    if (pos.start == pos.end)
      CompilerOffsetParamsUtils.fromPos(pos, token, outlineFiles)
    else
      CompilerRangeParamsUtils.fromPos(pos, token, outlineFiles)
  }

  def fromPos(
      pos: Position,
      token: CancelToken,
      outlineFiles: Optional[OutlineFiles] = Optional.empty()
  ): CompilerRangeParams = {
    val uri = syntaxURI(pos)
    CompilerRangeParams(
      uri,
      pos.input.text,
      pos.start,
      pos.end,
      token,
      outlineFiles
    )
  }
}

object CompilerOffsetParamsUtils extends OffsetParamsUtils {

  def fromPos(
      pos: Position,
      token: CancelToken,
      outlineFiles: Optional[OutlineFiles] = Optional.empty()
  ): CompilerOffsetParams = {
    val uri = syntaxURI(pos)
    CompilerOffsetParams(
      uri,
      pos.input.text,
      pos.start,
      token,
      outlineFiles
    )
  }
}

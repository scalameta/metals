package scala.meta.internal.metals

import java.net.URI
import java.util.Optional

import scala.meta.pc.CancelToken
import scala.meta.pc.OutlineFiles
import scala.meta.pc.VirtualFileParams

case class CompilerVirtualFileParams(
    uri: URI,
    text: String,
    token: CancelToken,
    override val outlineFiles: Optional[OutlineFiles]
) extends VirtualFileParams

object CompilerVirtualFileParams {
  def apply(
      uri: URI,
      text: String,
      token: CancelToken = EmptyCancelToken
  ): CompilerVirtualFileParams =
    CompilerVirtualFileParams(uri, text, token, Optional.empty())
}

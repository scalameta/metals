package scala.meta.internal.metals

import scala.meta.pc.CancelToken
import scala.meta.pc.VirtualFileParams
import java.net.URI

case class CompilerVirtualFileParams(
    uri: URI,
    text: String,
    token: CancelToken = EmptyCancelToken
) extends VirtualFileParams

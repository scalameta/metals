package scala.meta.internal.metals

import java.net.URI

import scala.meta.pc.CancelToken
import scala.meta.pc.VirtualFileParams

case class CompilerVirtualFileParams(
    uri: URI,
    text: String,
    token: CancelToken = EmptyCancelToken
) extends VirtualFileParams

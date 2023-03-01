package scala.meta.internal.metals

import java.net.URI
import scala.meta.internal.metals.EmptyCancelToken
import scala.meta.pc.{CancelToken, VirtualFileParams}

case class CompilerVirtualFileParams(
    uri: URI,
    text: String,
    token: CancelToken = EmptyCancelToken,
) extends VirtualFileParams

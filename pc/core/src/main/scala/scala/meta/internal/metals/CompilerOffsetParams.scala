package scala.meta.internal.metals

import scala.meta.pc.CancelToken
import scala.meta.pc.OffsetParams

case class CompilerOffsetParams(
    filename: String,
    text: String,
    offset: Int,
    token: CancelToken = EmptyCancelToken
) extends OffsetParams

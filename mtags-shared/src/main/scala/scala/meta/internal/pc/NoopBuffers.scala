package scala.meta.internal.pc

import java.net.URI
import java.util.Optional

import scala.meta.pc.Buffers
import scala.meta.pc.PcAdjustFileParams

object NoopBuffers extends Buffers {
  override def getFile(
      uri: URI,
      scalaVersion: String
  ): Optional[PcAdjustFileParams] = Optional.empty()

}

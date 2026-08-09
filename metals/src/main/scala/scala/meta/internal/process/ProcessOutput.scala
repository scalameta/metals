package scala.meta.internal.process

import java.io.OutputStream

sealed trait ProcessOutput

object ProcessOutput {

  final case class Lines(f: String => Unit) extends ProcessOutput
  final case class RawBytes(sink: OutputStream) extends ProcessOutput
}

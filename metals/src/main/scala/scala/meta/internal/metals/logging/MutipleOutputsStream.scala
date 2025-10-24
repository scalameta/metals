package scala.meta.internal.metals.logging

import java.io.OutputStream

class MutipleOutputsStream(outputs: List[OutputStream]) extends OutputStream {
  override def write(b: Int): Unit = outputs.foreach(_.write(b))

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    outputs.foreach(_.write(b, off, len))

  override def flush(): Unit = outputs.foreach(_.flush())

  override def close(): Unit = outputs.foreach(_.close())
}

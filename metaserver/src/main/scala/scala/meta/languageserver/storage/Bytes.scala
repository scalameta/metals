package scala.meta.languageserver.storage

import java.nio.charset.StandardCharsets

trait Bytes[A] { self =>
  def fromBytes(bytes: Array[Byte]): A
  def toBytes(e: A): Array[Byte]
  def map[B](f: A => B, g: B => A): Bytes[B] = new Bytes[B] {
    override def fromBytes(bytes: Array[Byte]) = f(self.fromBytes(bytes))
    override def toBytes(e: B): Array[Byte] = self.toBytes(g(e))
  }
}

object Bytes {
  implicit val StringBytes: Bytes[String] = new Bytes[String] {
    override def fromBytes(bytes: Array[Byte]): String =
      new String(bytes, StandardCharsets.UTF_8)
    override def toBytes(e: String): Array[Byte] =
      e.getBytes(StandardCharsets.UTF_8)
  }
  implicit val ByteArrayBytes: Bytes[Array[Byte]] = new Bytes[Array[Byte]] {
    override def toBytes(e: Array[Byte]): Array[Byte] = e
    override def fromBytes(bytes: Array[Byte]): Array[Byte] = bytes
  }
}

package scala.meta.metals.storage

import java.nio.charset.StandardCharsets
import org.langmeta.io.AbsolutePath
import scala.meta.internal.semanticdb3.TextDocuments

trait FromBytes[A] { self =>
  def fromBytes(bytes: Array[Byte]): A
  def map[B](f: A => B): FromBytes[B] =
    bytes => f(self.fromBytes(bytes))
}
object FromBytes {
  implicit val StringFromBytes: FromBytes[String] =
    new String(_, StandardCharsets.UTF_8)
  implicit val ByteArrayFromBytes: FromBytes[Array[Byte]] =
    identity[Array[Byte]]
  implicit val DatabaseFromBytes: FromBytes[TextDocuments] =
    bytes => TextDocuments.parseFrom(bytes)
}

trait ToBytes[A] { self =>
  def toBytes(e: A): Array[Byte]
  def contramap[B](f: B => A): ToBytes[B] =
    e => self.toBytes(f(e))
}
object ToBytes {
  implicit val StringToBytes: ToBytes[String] =
    _.getBytes(StandardCharsets.UTF_8)
  implicit val ByteArrayToBytes: ToBytes[Array[Byte]] =
    identity[Array[Byte]]
  implicit val DatabaseToBytes: ToBytes[TextDocuments] =
    _.toByteArray
  implicit val AbsolutePathToBytes: ToBytes[AbsolutePath] =
    _.toString().getBytes(StandardCharsets.UTF_8)
}

package scala.meta.internal.metals

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

object Compression {

  /**
   * Returns a GZIP deflated sequence of strings.
   */
  def compress(strings: Array[String]): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = CodedOutputStream.newInstance(baos)
    strings.foreach { member =>
      out.writeString(1, member)
    }
    out.flush()
    val protobuf = baos.toByteArray
    if (protobuf.isEmpty) Array.emptyByteArray
    else {
      val compressed = new ByteArrayOutputStream()
      val gzip = new GZIPOutputStream(compressed, protobuf.length)
      gzip.write(protobuf)
      gzip.finish()
      compressed.toByteArray
    }
  }

  /**
   * Returns a GZIP inflated sequence of strings.
   */
  def decompress(members: Array[Byte]): Array[String] = {
    if (members.isEmpty) Array.empty
    else {
      val gzip = new GZIPInputStream(new ByteArrayInputStream(members))
      val in = CodedInputStream.newInstance(gzip)
      val out = Array.newBuilder[String]
      var isDone = false
      while (!isDone && !in.isAtEnd) {
        val tag = in.readTag()
        tag match {
          case 10 =>
            out += in.readString()
          case 0 =>
            isDone = true
          case _ =>
            in.skipField(tag)
        }
      }
      out.result()
    }
  }

}

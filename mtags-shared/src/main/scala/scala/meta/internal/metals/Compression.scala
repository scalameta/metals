package scala.meta.internal.metals

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream

object Compression {

  /**
   * Returns a GZIP deflated sequence of classpath element parts.
   */
  def compress(strings: Iterator[ClasspathElementPart]): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val out = CodedOutputStream.newInstance(baos)
    strings.foreach {
      case m: PackageElementPart =>
        out.writeString(2, m.name)
      case m: ClassfileElementPart =>
        out.writeString(1, m.name)
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
   * Returns a GZIP inflated sequence of classfiles.
   */
  def decompress(members: Array[Byte]): Array[Classfile] = {
    if (members.isEmpty) Array.empty
    else {
      val gzip = new GZIPInputStream(new ByteArrayInputStream(members))
      val in = CodedInputStream.newInstance(gzip)
      val out = Array.newBuilder[Classfile]
      var pkg = ""
      var isDone = false
      while (!isDone && !in.isAtEnd) {
        val tag = in.readTag()
        tag match {
          case 10 /* magic tag for `writeString(1, ...)` */ =>
            val name = in.readString()
            out += Classfile(pkg, name)
          case 18 /* magic tag for `writeString(2, ...)` */ =>
            pkg = in.readString()
          case 0 =>
            isDone = true
          case tag =>
            in.skipField(tag)
        }
      }
      out.result()
    }
  }

}

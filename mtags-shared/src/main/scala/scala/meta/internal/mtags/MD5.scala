package scala.meta.internal.mtags

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.MessageDigest

import com.google.common.hash.Hashing
import com.google.common.io

object MD5 {
  def compute(string: String): String = {
    compute(ByteBuffer.wrap(string.getBytes(StandardCharsets.UTF_8)))
  }
  private def compute(buffer: ByteBuffer): String = {
    val md = MessageDigest.getInstance("MD5")
    md.update(buffer)
    bytesToHex(md.digest())
  }
  private val hexArray = "0123456789ABCDEF".toCharArray
  def bytesToHex(bytes: Array[Byte]): String = {
    val hexChars = new Array[Char](bytes.length * 2)
    var j = 0
    while (j < bytes.length) {
      val v: Int = bytes(j) & 0xff
      hexChars(j * 2) = hexArray(v >>> 4)
      hexChars(j * 2 + 1) = hexArray(v & 0x0f)
      j += 1
    }
    new String(hexChars)
  }

  def compute(path: Path): String = {
    io.Files.asByteSource(path.toFile).hash(Hashing.md5()).toString()
  }
}

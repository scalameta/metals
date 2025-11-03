package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object OID {

  def fromText(text: String): String = {
    fromBlob(text.getBytes(StandardCharsets.UTF_8))
  }

  /**
   * Computes the content-addressed Git "OID" for the given binary data.
   *
   * This is a useful cache key for any indexing system that is based on
   * individual files (like extracting mtags toplevel symbols) because it's
   * almost instant to list all files and their OIDs in a given repo with the
   * command `git ls-files --stage`.
   */
  def fromBlob(bytes: Array[Byte]): String = {
    val digest = MessageDigest.getInstance("SHA-1")
    val header = s"blob ${bytes.length}\u0000"
    digest.update(header.getBytes(StandardCharsets.UTF_8))
    digest.update(bytes)
    val oid = digest.digest()
    val hex = oid.map("%02x".format(_)).mkString
    hex
  }
}

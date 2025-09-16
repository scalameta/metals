package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.util.Arrays

import scala.meta.internal.metals.StringBloomFilter
import scala.meta.internal.semanticdb.Language
import scala.meta.internal.semanticdb.TextDocument

/**
 *  A git blob with optional metadata like the text contents, parse SemanticDB, or a bloom filter from symbol indexing.
 *
 * The optional metadata fields are mutable to allow for lazy loading of the metadata.
 */
final class GitBlob(
    val path: String,
    val oidBytes: Array[Byte],
    var textBytes: Array[Byte] = null,
    var semanticdb: TextDocument = null,
    var bloomFilter: StringBloomFilter = null,
) {
  override def toString(): String =
    s"GitBlob(path=$path, oid=$oid, textBytes=${textBytes != null}, semanticdb=${semanticdb != null}, bloomFilter=${bloomFilter != null})"
  override def hashCode(): Int = Arrays.hashCode(oidBytes)
  override def equals(that: Any): Boolean = that match {
    case that: GitBlob => Arrays.equals(oidBytes, that.oidBytes)
    case _ => false
  }
  def oid: String = new String(oidBytes, StandardCharsets.UTF_8)
  def toLanguage: Language = {
    if (path.endsWith(".java")) {
      Language.JAVA
    } else if (path.endsWith(".scala")) {
      Language.SCALA
    } else {
      Language.UNKNOWN_LANGUAGE
    }
  }
}

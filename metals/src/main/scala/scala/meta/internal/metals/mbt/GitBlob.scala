package scala.meta.internal.metals.mbt

import java.nio.charset.StandardCharsets
import java.util.Arrays

import scala.meta.internal.jsemanticdb.Semanticdb
import scala.meta.internal.metals.StringBloomFilter
import scala.meta.internal.mtags.DocumentToplevels
import scala.meta.internal.mtags.ScalametaCommonEnrichments
import scala.meta.internal.semanticdb.TextDocument
import scala.meta.io.AbsolutePath

/**
 *  A git blob with optional metadata like the text contents, parse SemanticDB, or a bloom filter from symbol indexing.
 *
 * The optional metadata fields are mutable to allow for lazy loading of the metadata.
 */
final class GitBlob(
    val path: String,
    var oidBytes: Array[Byte],
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
  def toOIDIndex(workspace: AbsolutePath): OIDIndex = {
    val toplevels =
      if (semanticdb == null) DocumentToplevels.empty
      else DocumentToplevels.fromDocument(semanticdb)
    OIDIndex(
      this.toJLanguage,
      this.oidBytes,
      workspace.resolve(path).toNIO,
      this.bloomFilter,
      toplevels.pkg,
      toplevels.toplevels,
    )
  }

  def toJLanguage: Semanticdb.Language = {
    ScalametaCommonEnrichments.filenameToJLanguage(path)
  }
}

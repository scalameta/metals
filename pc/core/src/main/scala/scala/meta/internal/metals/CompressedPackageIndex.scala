package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import scala.collection.JavaConverters._

/**
 * The memory-compressed version of PackageIndex.
 *
 * @param bloom the fuzzy search bloom filter for all members of this package.
 * @param memberBytes the GZIP compressed bytes representing Array[String] for
 *   all members of this package. The members are compressed because the strings
 *   consume a lot of memory and most queries only require looking at a few packages.
 *   We decompress the members only when a search query matches the bloom filter
 *   for this package.
 */
case class CompressedPackageIndex(
    bloom: BloomFilter[CharSequence],
    memberBytes: Array[Byte]
) {
  def members: Array[String] = Compression.decompress(memberBytes)
}

object CompressedPackageIndex {
  private def isExcludedPackage(pkg: String): Boolean = {
    // NOTE(olafur) At some point we may consider making this list configurable, I can
    // imagine that some people wouldn't mind excluding more packages or including for
    // example javax._.
    pkg.startsWith("jdk/") ||
    pkg.startsWith("sun/") ||
    pkg.startsWith("javax/") ||
    pkg.startsWith("oracle/") ||
    pkg.startsWith("org/omg/") ||
    pkg.startsWith("com/oracle/") ||
    pkg.startsWith("com/sun/") ||
    pkg.startsWith("com/apple/")
  }
  def fromPackages(
      packages: PackageIndex
  ): Iterator[(String, CompressedPackageIndex)] = {
    for {
      (pkg, members) <- packages.packages.asScala.iterator
      if !isExcludedPackage(pkg)
    } yield {
      val buf = Fuzzy.bloomFilterSymbolStrings(members.asScala)
      buf ++= Fuzzy.bloomFilterSymbolStrings(List(pkg), buf)
      val bloom = BloomFilters.create(buf.size)
      buf.foreach { key =>
        bloom.put(key)
      }
      // Sort members for deterministic order for deterministic results.
      members.sort(String.CASE_INSENSITIVE_ORDER)
      // Compress members because they make up the bulk of memory usage in the classpath index.
      // For a 140mb classpath with spark/linkerd/akka/.. the members take up 12mb uncompressed
      // and ~900kb compressed. We are accummulating a lot of different custom indexes in Metals
      // so we should try to keep each of them as small as possible.
      val compressedMembers = Compression.compress(members.asScala.iterator)
      (pkg, CompressedPackageIndex(bloom, compressedMembers))
    }
  }
}

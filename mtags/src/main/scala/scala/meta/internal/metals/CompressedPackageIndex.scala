package scala.meta.internal.metals

import scala.collection.JavaConverters._
import java.{util => ju}

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
    bloom: StringBloomFilter,
    memberBytes: Array[Byte]
) {
  def members: Array[String] = Compression.decompress(memberBytes)
}

object CompressedPackageIndex {
  def isExcludedPackage(pkg: String): Boolean = {
    // NOTE(olafur) At some point we may consider making this list configurable, I can
    // imagine that some people wouldn't mind excluding more packages or including for
    // example javax._.
    pkg.startsWith("META-INF/") ||
    pkg.startsWith("images/") ||
    pkg.startsWith("toolbarButtonGraphics/") ||
    pkg.startsWith("jdk/") ||
    pkg.startsWith("sun/") ||
    pkg.startsWith("javax/") ||
    pkg.startsWith("oracle/") ||
    pkg.startsWith("java/awt/desktop/") ||
    pkg.startsWith("org/jcp/") ||
    pkg.startsWith("org/omg/") ||
    pkg.startsWith("org/graalvm/") ||
    pkg.startsWith("com/oracle/") ||
    pkg.startsWith("com/sun/") ||
    pkg.startsWith("com/apple/") ||
    pkg.startsWith("apple/")
  }
  def fromPackages(
      packages: PackageIndex
  ): Iterator[(String, CompressedPackageIndex)] = {
    for {
      (pkg, members) <- packages.packages.asScala.iterator
      if !isExcludedPackage(pkg)
    } yield {
      val bloom = Fuzzy.bloomFilterSymbolStrings(members.asScala)
      Fuzzy.bloomFilterSymbolStrings(List(pkg), bloom)
      // Sort members for deterministic order for deterministic results.
      val membersSeq = new ju.ArrayList[String](members.size())
      membersSeq.addAll(members)
      membersSeq.sort(String.CASE_INSENSITIVE_ORDER)
      // Compress members because they make up the bulk of memory usage in the classpath index.
      // For a 140mb classpath with spark/linkerd/akka/.. the members take up 12mb uncompressed
      // and ~900kb compressed. We are accummulating a lot of different custom indexes in Metals
      // so we should try to keep each of them as small as possible.
      val compressedMembers = Compression.compress(members.asScala.iterator)
      (pkg, CompressedPackageIndex(bloom, compressedMembers))
    }
  }
}

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
    packages: Array[String],
    bloom: StringBloomFilter,
    memberBytes: Array[Byte]
) {
  require(packages.nonEmpty)
  def members: Array[Classfile] = {
    Compression.decompress(memberBytes)
  }
  def compare(other: CompressedPackageIndex): Int = {
    packages(0).compare(other.packages(0))
  }
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
  ): Array[CompressedPackageIndex] = {
    val buf = Array.newBuilder[CompressedPackageIndex]
    val members = new ju.ArrayList[ClasspathElementPart]()
    def defaultBlomFilter() = new StringBloomFilter(500)
    val bufPackages = Array.newBuilder[String]
    var bloom = defaultBlomFilter()
    def flush(): Unit = {
      // Compress members because they make up the bulk of memory usage in the classpath index.
      // For a 140mb classpath with spark/linkerd/akka/.. the members take up 12mb uncompressed
      // and ~900kb compressed. We are accummulating a lot of different custom indexes in Metals
      // so we should try to keep each of them as small as possible.
      val compressedMembers = Compression.compress(members.asScala.iterator)
      buf += CompressedPackageIndex(
        bufPackages.result(),
        bloom,
        compressedMembers
      )
    }
    def enterPackage(pkg: String): Unit = {
      bufPackages += pkg
      members.add(PackageElementPart(pkg))
      Fuzzy.bloomFilterSymbolStrings(pkg, bloom)
    }
    def newBloom(): Unit = {
      flush()
      bloom = defaultBlomFilter()
      bufPackages.clear()
      members.clear()
    }
    for {
      (pkg, packageMembers) <- packages.packages.asScala.iterator
      if !isExcludedPackage(pkg)
    } {
      enterPackage(pkg)
      // Sort members for deterministic order for deterministic results.
      val sortedMembers = new ju.ArrayList[String](packageMembers.size())
      sortedMembers.addAll(packageMembers)
      sortedMembers.sort(String.CASE_INSENSITIVE_ORDER)
      sortedMembers.forEach { member =>
        if (bloom.isFull) {
          newBloom()
          enterPackage(pkg)
        }
        members.add(ClassfileElementPart(member))
        Fuzzy.bloomFilterSymbolStrings(member, bloom)
      }
    }
    flush()
    buf.result()
  }
}

package scala.meta.internal.metals

import scala.meta.internal.jdk.CollectionConverters._
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

  /**
   * The default size of bloom filters that are used for fuzzy symbol search.
   *
   * The default value was chosen based on two criteria: cpu usage and memory
   * usage. Here are the performance results of a searching for the query
   * "File" with different bucket sizes.
   *
   * {{{
   *   Benchmark               (bucketSize)  (query)  Mode  Cnt    Score   Error  Units
   *   ClasspathFuzzBench.run           128     File    ss   50   77.908 ± 6.572  ms/op
   *   ClasspathFuzzBench.run           256     File    ss   50   75.587 ± 3.173  ms/op
   *   ClasspathFuzzBench.run           512     File    ss   50   74.154 ± 3.751  ms/op
   *   ClasspathFuzzBench.run          1024     File    ss   50   81.825 ± 4.910  ms/op
   *   ClasspathFuzzBench.run          2048     File    ss   50   92.891 ± 5.241  ms/op
   *   ClasspathFuzzBench.run          4096     File    ss   50  101.436 ± 3.746  ms/op
   *   ClasspathFuzzBench.run          8192     File    ss   50  106.969 ± 3.971  ms/op
   * }}}
   *
   * There query performance goes down with larger bucket sizes presumably
   * because for every hit we need to walk a lot of redudant classfile names.
   *
   * Here is the memory usage of differently sized bloom filters.
   *
   * | Size | Total memory   | Ratio             |
   * | ---  | -------------- | ----------------- |
   * | 512  | 776 bytes      | 1.52 byte/element |
   * | 1024 | 1.21 kilobytes | 1.18 byte/element |
   * | 2048 | 2.13 kilobytes | 1.04 byte/element |
   *
   * For a classpath of 235Mb, the size of the index is 3.4Mb with
   * bucketSize=512 and 1.8Mb with bucketSize=1024.
   *
   * Given these observations, both 512 and 1024 seem like reasonable defaults
   * so we'll go with 512 since it seems to squeeze out a tiny bit more
   * performance at the cost of a small additional memory usage.
   */
  val DefaultBucketSize = 512

  /**
   * Return an index the classpath elements for fast fuzzy symbol search.
   *
   * @param packages the map from all packages to their member classfile paths.
   * @param bucketSize the maximum number of classpath elements in each returned
   *                   compressed package index.
   */
  def fromPackages(
      packages: PackageIndex,
      bucketSize: Int = DefaultBucketSize
  ): Array[CompressedPackageIndex] = {
    // The final result.
    val buckets = Array.newBuilder[CompressedPackageIndex]
    // The current pending package names and classfile names to be compressed.
    val members = new ju.ArrayList[ClasspathElementPart]()
    // The current pending package names that are kept uncompressed.
    val bufPackages = Array.newBuilder[String]
    // The bloom filter representing the set of all the possible queries that match
    // a classfile in the current pending bucket.
    var bucket = new StringBloomFilter(bucketSize)

    // Save the current bucket.
    def flushBucket(): Unit = {
      // Compress members because they make up the bulk of memory usage in the classpath index.
      // For a 140mb classpath with spark/linkerd/akka/.. the members take up 12mb uncompressed
      // and ~900kb compressed. We are accummulating a lot of different custom indexes in Metals
      // so we should try to keep each of them as small as possible.
      val compressedMembers = Compression.compress(members.asScala.iterator)
      buckets += CompressedPackageIndex(
        bufPackages.result(),
        bucket,
        compressedMembers
      )
    }

    // Record the start of a new package.
    def enterPackage(pkg: String): Unit = {
      bufPackages += pkg
      members.add(PackageElementPart(pkg))
      Fuzzy.bloomFilterSymbolStrings(pkg, bucket)
    }

    // Save the current bucket and start a new one.
    def newBucket(): Unit = {
      flushBucket()
      bucket = new StringBloomFilter(bucketSize)
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
        if (bucket.isFull) {
          newBucket()
          enterPackage(pkg)
        }
        members.add(ClassfileElementPart(member))
        Fuzzy.bloomFilterSymbolStrings(member, bucket)
      }
    }

    flushBucket()
    buckets.result()
  }
}

package scala.meta.internal.metals

import com.google.common.hash.BloomFilter

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

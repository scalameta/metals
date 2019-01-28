package scala.meta.internal.metals

import com.google.common.hash.BloomFilter

case class CompressedSourceIndex(
    bloom: BloomFilter[CharSequence],
    // TODO(olafur): actually compress these
    symbols: Seq[CachedSymbolInformation]
)

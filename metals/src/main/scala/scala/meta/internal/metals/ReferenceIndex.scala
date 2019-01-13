package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import java.nio.file.Path

case class ReferenceIndex(
    blooms: collection.Map[Path, BloomFilter[CharSequence]]
)

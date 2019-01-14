package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import java.nio.charset.StandardCharsets

object BloomFilters {
  def create(size: Int): BloomFilter[CharSequence] = {
    BloomFilter.create(
      Funnels.stringFunnel(StandardCharsets.UTF_8),
      Integer.valueOf(size),
      0.01
    )
  }
}

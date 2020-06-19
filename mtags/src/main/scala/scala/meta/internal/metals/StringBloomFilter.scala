package scala.meta.internal.metals

import com.google.common.hash.BloomFilter
import com.google.common.hash.Funnels
import net.jpountz.xxhash.XXHashFactory

/**
 * A wrapper around a bloom filter that is optimized for fast insertions of
 * strings with shared prefixes.
 *
 * To index a classpath, Metals walks through all possible prefixes of a given
 * classfile path. For example, given the string "InputStream.class" Metals
 * builds a bloom filter containing the set of the following strings: I, In,
 * Inp, Inpu, Input, S, St, Str, Stre, Strea and Stream.
 *
 * The naive approach to construct a bloom filter of all those prefix strings
 * is to create a `BloomFilter[CharSequence]` and insert all those prefixes.
 * This approach has sub-optimal performance because it requires a quadratic
 * number of iterations on the characters of the classfile path.
 *
 * This class implements an optimized approach to build that bloom filter in a
 * way that requires only a linear pass on the characters of the classfile
 * path. The trick is to construct a `BloomFilter[Long]` instead of
 * `BloomFilter[CharSequence]` and incrementally build the hashcode of each
 * prefix string as we iterate over each character in the string.
 *
 * Additionally, this class exposes a `mightContain(Long)` method that speeds
 * up search queries by allowing the client to pre-compute the hash of the
 * query string and re-use `mightContain` calls to multiple bloom filters. For
 * every single fuzzy symbol search (which happens on every single scope
 * completion request) we usually perform several thousant bloom filter
 * `mightContain` calls so should also help avoid a non-trivial amount of
 * unnecessary hashing.
 */
class StringBloomFilter(estimatedSize: Int) {
  val maxFalsePositiveRatio = 0.01
  val bloom: BloomFilter[java.lang.Long] = BloomFilter.create(
    Funnels.longFunnel(),
    Integer.valueOf(estimatedSize),
    maxFalsePositiveRatio
  )
  def isFull: Boolean = bloom.expectedFpp() > maxFalsePositiveRatio
  // NOTE(olafur): we don't use the XXHashFactory.fastestInstance() instance
  // because the docstring warns about using the native hash instance in
  // applications where multiple isolated classloaders run on the same JVM,
  // which is the case in Metals where we isolate the mtags classloader for
  // Scala 2.11/2.12/2.13.
  private val factory = XXHashFactory.fastestJavaInstance()
  private val streamingHash = factory.newStreamingHash64(1234)
  // Chars are 16 bit so we need two bytes per character.
  private val buffer = new Array[Byte](2)

  /**
   * Resets the hash value. */
  def reset(): Unit = streamingHash.reset()

  /**
   * Returns the current hash value. */
  def value(): Long = streamingHash.getValue()

  /**
   * Inserts a new string to the bloom filter that is the concatenation of the
   * current hash value and the given character.
   *
   * Use this method when inserting multiple strings that share the same prefix,
   * for example to insert all prefixes of "Simple" you can do
   * {{{
   *   putCharIncrementally('S') // insert S
   *   putCharIncrementally('i') // insert Si
   *   putCharIncrementally('m') // insert Sim
   *   putCharIncrementally('p') // insert Simp
   *   putCharIncrementally('l') // insert Simpl
   *   putCharIncrementally('e') // insert Simple
   * }}}
   */
  def putCharIncrementally(char: Char): Boolean = {
    updateHashCode(char)
    bloom.put(value())
  }

  private def updateHashCode(char: Char): Unit = {
    buffer(0) = char.toByte
    buffer(1) = (char >> 8).toByte
    streamingHash.update(buffer, 0, 2)
  }

  /**
   * Insert a single string into the bloom filter. */
  def putCharSequence(chars: CharSequence): Boolean = {
    bloom.put(computeHashCode(chars))
  }

  /**
   * Computes the hascode of a single string that can be later passed to `mightContain(Long)`. */
  def computeHashCode(chars: CharSequence): Long = {
    streamingHash.reset()
    var i = 0
    val N = chars.length()
    while (i < N) {
      updateHashCode(chars.charAt(i))
      i += 1
    }
    value()
  }

  /**
   * Returns true if the bloom filter contains the given string. */
  def mightContain(chars: CharSequence): Boolean = {
    bloom.mightContain(computeHashCode(chars))
  }

  /**
   * Returns true if the bloom filter contains the string with the given hashcode.
   *
   * This method is can help improve performance when calling `mightContain`
   * with the same query string to multiple different bloom filters.
   */
  def mightContain(hashCode: Long): Boolean = {
    bloom.mightContain(hashCode)
  }

  def approximateElementCount(): Long = {
    bloom.approximateElementCount()
  }

}

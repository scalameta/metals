package scala.meta.internal.metals

import java.lang.StringBuilder

/**
 * Returns all possible substrings of length 3 for the given string.
 */
class TrigramSubstrings(string: String) extends Traversable[String] {
  override def foreach[U](f: String => U): Unit = {
    val N = string.length
    val arr = new Array[Char](3)
    var i = 0
    while (i < N) {
      var j = i + 1
      while (j < N) {
        var k = j + 1
        while (k < N) {
          arr(0) = string.charAt(i)
          arr(1) = string.charAt(j)
          arr(2) = string.charAt(k)
          f(new String(arr))
          k += 1
        }
        j += 1
      }
      i += 1
    }
  }
}

object TrigramSubstrings {

  /**
   * Returns combinations of the query string with up to three characters uppercased.
   *
   * We assume the first character is always uppercased so this method is more like
   * "bigrams for `query.tail`".
   *
   * @param maxCount the maximum number of combinations. The default value 250 is chosen mostly arbitrarily
   *                 with basic validation through benchmarks in akka/akka. The query "abcdefghijklmnopqrstabcdefghijkl",
   *                 which returns 0 results, takes 215ms in akka/akka with 250 combinations and 420ms with 500 combinations.
   */
  def uppercased(
      query: String,
      maxCount: Int = 250
  ): Traversable[String] = new Traversable[String] {
    override def foreach[U](f: String => U): Unit = {
      var continue = true
      var count = 0
      val first = query.head.toUpper
      def emit(string: String): Unit = {
        count += 1
        if (count > maxCount) {
          continue = false
        } else {
          f(string)
        }
      }
      for {
        i <- 1.until(query.length)
        if continue
        bigram = new StringBuilder()
          .append(first)
          .append(query.subSequence(1, i))
          .append(query.charAt(i).toUpper)
          .append(query.subSequence(i + 1, query.length))
          .toString
        _ = emit(bigram)
        j <- (i + 1).until(query.length)
        if continue
      } {
        val trigram = new StringBuilder()
          .append(first)
          .append(query.subSequence(1, i))
          .append(query.charAt(i).toUpper)
          .append(query.subSequence(i + 1, j))
          .append(query.charAt(j).toUpper)
          .append(query.subSequence(j + 1, query.length))
          .toString
        emit(trigram)
      }
    }
  }
}

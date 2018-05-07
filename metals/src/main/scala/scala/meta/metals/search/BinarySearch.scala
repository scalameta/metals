package scala.meta.metals.search

import scala.annotation.tailrec

object BinarySearch {
  sealed trait ComparisonResult
  case object Greater extends ComparisonResult
  case object Equal extends ComparisonResult
  case object Smaller extends ComparisonResult

  def array[T](arr: Array[T], compare: T => ComparisonResult): Option[T] =
    apply[T](i => arr(i), compare, arr.length)

  /**
   * Binary search using a custom compare function.
   *
   * scala.util.Searching does not support the ability to search an IndexedSeq
   * by a custom mapping function, you must search by an element of the same
   * type as the elements of the Seq.
   *
   * @param lookup Must be sorted according to compare function so that for all
   *              i > j, compare(array(i), array(i)) == Greater.
   * @param compare Callback used at every guess index to determine whether
   *                the search element has been found, or whether to search
   *                above or below the guess index.
   * @return The first element where compare(element) == Equal. There is no guarantee
   *         which element is chosen if many elements return Equal.
   */
  def apply[T](
      lookup: Int => T,
      compare: T => ComparisonResult,
      length: Int
  ): Option[T] = {
    @tailrec def loop(lo: Int, hi: Int): Option[T] =
      if (lo > hi) None
      else {
        val mid = lo + (hi - lo) / 2
        val curr = lookup(mid)
        compare(curr) match {
          case Greater => loop(lo, mid - 1)
          case Equal => Some(curr)
          case Smaller => loop(mid + 1, hi)
        }
      }
    loop(0, length - 1)
  }
}

package scala.meta.languageserver.search

import scala.annotation.tailrec

object BinarySearch {
  sealed trait ComparisonResult
  case object Greater extends ComparisonResult
  case object Equal extends ComparisonResult
  case object Smaller extends ComparisonResult

  def array[T](
      array: Array[T],
      matches: T => ComparisonResult
  ): Option[T] = {
    @tailrec def loop(lo: Int, hi: Int): Option[T] =
      if (lo > hi) None
      else {
        val mid = lo + (hi - lo) / 2
        val curr = array(mid)
        matches(curr) match {
          case Greater => loop(lo, mid - 1)
          case Equal => Some(curr)
          case Smaller => loop(mid + 1, hi)
        }
      }
    loop(0, array.length - 1)
  }
}

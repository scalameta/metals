package example

trait Ord[T]:
  def compare(x: T, y: T): Int

given intOrd: Ord[Int] with
  def compare(x: Int, y: Int)/*: Int*/ =
    if x < y then -1 else if x > y then +1 else 0

given Ord[String] with
  def compare(x: String, y: String)/*: Int*/ =
    /*augmentString(*/x/*)*/.compare(y)
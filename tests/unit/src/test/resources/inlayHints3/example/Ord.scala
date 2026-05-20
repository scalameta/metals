package example

trait Ord[T]:
  def compare(x: T, y: T): Int

given intOrd: Ord[Int] with
  def compare(x: Int, y: Int)/*: Int<<scala/Int#>>*/ =
    if x < y then -1 else if x > y then +1 else 0

given Ord[String] with
  def compare(x: String, y: String)/*: Int<<scala/Int#>>*/ =
    /*augmentString<<scala/Predef.augmentString().>>(*/x/*)*/.compare(/*that = */y)

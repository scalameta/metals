package example

object Main {
  val l: shapeless.HList = ???
  import shapeless._
  l match {
    case head :: tail =>
    case HNil         =>
  }
}

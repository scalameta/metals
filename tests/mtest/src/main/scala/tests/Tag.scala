package tests

sealed abstract class Tag
object Tag {
  case object ExpectFailure extends Tag
}

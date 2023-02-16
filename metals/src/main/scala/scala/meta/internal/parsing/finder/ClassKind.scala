package scala.meta.internal.parsing.finder

sealed trait ClassKind
object ClassKind {
  case object Class extends ClassKind
  case object Trait extends ClassKind
  case object Object extends ClassKind
  case object Enum extends ClassKind
  case object ToplevelPackage extends ClassKind
}

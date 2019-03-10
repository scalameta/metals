package scala.meta.internal.pc

sealed abstract class CompletionListKind
object CompletionListKind {
  case object None extends CompletionListKind
  case object Type extends CompletionListKind
  case object Scope extends CompletionListKind
}

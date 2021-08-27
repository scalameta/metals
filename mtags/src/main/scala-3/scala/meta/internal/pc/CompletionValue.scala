package scala.meta.internal.pc

import dotty.tools.dotc.interactive.Completion

enum CompletionValue {
  case NamedArg(v: Completion)
  case Scope(v: Completion)
  case Workspace(v: Completion)
  case Compiler(v: Completion)

  def value: Completion = this match {
    case Workspace(v) => v
    case Compiler(v) => v
    case NamedArg(v) => v
    case Scope(v) => v
  }
}

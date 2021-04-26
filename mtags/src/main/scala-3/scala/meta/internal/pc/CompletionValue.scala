package scala.meta.internal.pc

import dotty.tools.dotc.interactive.Completion

enum CompletionValue {
  case Workspace(v: Completion)
  case Compiler(v: Completion)
  case NamedArg(v: Completion)

  def value: Completion = this match {
    case Workspace(v) => v
    case Compiler(v) => v
    case NamedArg(v) => v
  }
}

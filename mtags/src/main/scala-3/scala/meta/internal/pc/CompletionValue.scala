package scala.meta.internal.pc

import dotty.tools.dotc.interactive.Completion

enum CompletionValue {
  case Workspace(v: Completion)
  case Compiler(v: Completion)
  case NamedArg(v: Completion)
  case Scope(v: Completion)

  def value: Completion = this match {
    case Workspace(v) => v
    case Compiler(v) => v
    case NamedArg(v) => v
    case Scope(v) => v
  }

  def priority: Int = this match {
    case NamedArg(v) => 1
    case Scope(v) => 2
    case Compiler(v) => 3
    case Workspace(v) => 4
  }
}

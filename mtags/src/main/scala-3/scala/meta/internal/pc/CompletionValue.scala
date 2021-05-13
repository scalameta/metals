package scala.meta.internal.pc

import dotty.tools.dotc.interactive.Completion

/**
 * That is a termporal emulation of `scala.tools.nsc.interactive.CompilerControl.Member`
 * It's used in completions ordering.
 * Curently it's done only by `source of completion` and far from begin ideal.
 * Should be improved in future.
 */
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

  def priority: Int = this match {
    case NamedArg(v) => 1
    case Scope(v) => 2
    case Compiler(v) => 3
    case Workspace(v) => 4
  }
}

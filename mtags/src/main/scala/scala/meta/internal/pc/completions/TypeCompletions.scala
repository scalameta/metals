package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal

trait TypeCompletions { this: MetalsGlobal =>

  /**
   * A completion inside a type position, example `val x: Map[Int, Strin@@]`
   */
  case object TypeCompletion extends CompletionPosition {
    override def isType: Boolean = true
  }

}

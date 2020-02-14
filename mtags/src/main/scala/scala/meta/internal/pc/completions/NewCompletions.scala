package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal

trait NewCompletions { this: MetalsGlobal =>

  /**
   * A completion inside a new expression, example `new Array@@`
   */
  case object NewCompletion extends CompletionPosition {
    override def isNew: Boolean = true
  }

}

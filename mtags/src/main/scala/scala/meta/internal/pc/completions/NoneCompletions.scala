package scala.meta.internal.pc.completions

import scala.meta.internal.pc.MetalsGlobal

trait NoneCompletions { this: MetalsGlobal =>

  case object NoneCompletion extends CompletionPosition

}

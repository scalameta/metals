package scala.meta.internal.pc

import scala.meta.pc.ParamNameHintResult

import org.eclipse.lsp4j.Range

case class ParamNameHintResultImpl(range: Range, contentText: String)
    extends ParamNameHintResult

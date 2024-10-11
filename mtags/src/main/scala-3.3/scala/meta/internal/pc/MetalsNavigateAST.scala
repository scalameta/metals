package scala.meta.internal.pc

import dotty.tools.dotc.ast.NavigateAST
import dotty.tools.dotc.ast.untpd.ExtMethods
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.Spans.Span
import dotty.tools.dotc.ast.Positioned

object MetalsNavigateAST:
  def pathToExtensionParam(span: Span, methods: ExtMethods)(using Context): List[Positioned] =
    NavigateAST.pathTo(span, methods.paramss.flatten)

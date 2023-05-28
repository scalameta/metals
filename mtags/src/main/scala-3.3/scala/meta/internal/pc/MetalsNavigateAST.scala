package scala.meta.internal.pc

import dotty.tools.dotc.ast.NavigateAST
import dotty.tools.dotc.ast.untpd.ExtMethods
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.Spans.Span

object MetalsNavigateAST:
  def pathToExtensionParam(span: Span, methods: ExtMethods)(using Context) =
    NavigateAST.pathTo(span, methods.paramss.flatten)

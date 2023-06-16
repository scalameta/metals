package scala.meta.internal.pc

import dotty.tools.dotc.ast.NavigateAST
import dotty.tools.dotc.ast.Positioned
import dotty.tools.dotc.ast.untpd.ExtMethods
import dotty.tools.dotc.core.Contexts.Context
import dotty.tools.dotc.util.Spans.Span

object MetalsNavigateAST:
  // normally we use NavigateAST.pathTo, but its signature changes between 3.1.2 and 3.1.3
  def pathToExtensionParam(span: Span, methods: ExtMethods)(using Context) =
    val untypedPath = NavigateAST.untypedPath(span)
    val cropped = untypedPath.takeWhile {
      case ExtMethods(paramss, _)
          if paramss.flatten.exists(_.span.contains(span)) =>
        false
      case _ => true
    }
    if cropped.length == untypedPath.length then List.empty
    else cropped

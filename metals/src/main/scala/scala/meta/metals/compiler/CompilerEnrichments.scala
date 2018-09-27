package scala.meta.metals.compiler

import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global
import scala.meta.metals.MetalsLogger
import scala.{meta => m}

object CompilerEnrichments extends MetalsLogger {

  def safeCompletionsAt[G <: Global](
      global: Global,
      position: Position
  ): List[global.CompletionResult#M] = {
    def expected(e: Throwable): List[Nothing] = {
      logger.warn(s"Expected error '${e.getMessage}'")
      Nil
    }
    try {
      global.completionsAt(position).matchingResults().distinct
    } catch {
      case e: global.CyclicReference
          if e.getMessage.contains("illegal cyclic reference") =>
        // A quick google search reveals this happens regularly and there is
        // no general fix for it.
        expected(e)
      case e: ScalaReflectionException
          if e.getMessage.contains("not a module") =>
        // Do nothing, seems to happen regularly
        // scala.ScalaReflectionException: value <error: object a> is not a module
        // at scala.reflect.api.Symbols$SymbolApi.asModule(Symbols.scala:250)
        // at scala.reflect.api.Symbols$SymbolApi.asModule$(Symbols.scala:250)
        expected(e)
      case e: NullPointerException =>
        // do nothing, seems to happen regularly
        // java.lang.NullPointerException: null
        //   at scala.tools.nsc.Global$GlobalPhase.cancelled(Global.scala:408)
        //   at scala.tools.nsc.Global$GlobalPhase.applyPhase(Global.scala:418)
        //   at scala.tools.nsc.Global$Run.$anonfun$compileLate$3(Global.scala:1572)
        expected(e)
      case e: StringIndexOutOfBoundsException =>
        // NOTE(olafur) Let's log this for now while we are still learning more
        // about the PC. However, I haven't been able to avoid this exception
        // in some cases so I suspect it's here to stay until we fix it upstream.
        expected(e)
    }
  }

  implicit class XtensionPositionScalac(val pos: Position) extends AnyVal {
    def toMeta(input: m.Input): m.Position = {
      if (pos.isRange) m.Position.Range(input, pos.start, pos.end)
      else if (pos.isDefined) m.Position.Range(input, pos.point, pos.point)
      else m.Position.None
    }
  }
}

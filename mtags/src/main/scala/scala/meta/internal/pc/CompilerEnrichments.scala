package scala.meta.internal.pc

import scala.reflect.internal.util.Position
import scala.tools.nsc.interactive.Global

object CompilerEnrichments {
  def safeCompletionsAt[G <: Global](
      global: Global,
      position: Position
  ): List[global.CompletionResult#M] = {
    def expected(e: Throwable): List[Nothing] = {
      println(s"Expected error '${e.getMessage}'")
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
}

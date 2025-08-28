package scala.meta.internal.pc

import scala.reflect.internal.util.CodeAction
import scala.reflect.internal.util.Position
import scala.tools.nsc.Settings
import scala.tools.nsc.interactive.Global
import scala.tools.nsc.interactive.InteractiveReporter

class MetalsReporter(initSettings: Settings) extends InteractiveReporter {
  private[pc] var _metalsGlobal: MetalsGlobal = null
  override def compiler: Global = _metalsGlobal

  override def settings: Settings = initSettings

  override def doReport(
      pos: Position,
      msg: String,
      severity: Severity,
      actions: List[CodeAction]
  ): Unit = {
    if (severity == INFO)
      System.err.println(msg)
    else
      super.doReport(pos, msg, severity, actions)
  }
}

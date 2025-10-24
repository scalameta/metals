package scala.meta.internal.metals.logging

import scribe._
import scribe.modify.LogModifier

object MetalsSingletonLogFilter extends MetalsFilter()

case class MetalsFilter(id: String = "MetalsFilter") extends LogModifier {
  override def withId(id: String): LogModifier = copy(id = id)
  override def priority: Priority = Priority.Normal
  override def apply(record: LogRecord): Option[LogRecord] = {
    if (
      record.className.startsWith("org.flywaydb") &&
      record.level < scribe.Level.Warn.value
    ) {
      None
    } else {
      Some(record)
    }
  }

}

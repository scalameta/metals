package scala.meta.languageserver.compiler

import scala.reflect.internal.util.Position
import com.typesafe.scalalogging.LazyLogging

object CompilerEnrichments extends LazyLogging {
  implicit class XtensionPositionGlobal(val position: Position) extends AnyVal {
    def print(): Unit = {
      logger.info(position.lineContent)
      logger.info(position.lineCaret)
    }
  }
}

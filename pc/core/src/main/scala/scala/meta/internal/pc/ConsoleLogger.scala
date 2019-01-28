package scala.meta.internal.pc
import java.util.logging.ConsoleHandler
import java.util.logging.Level
import java.util.logging.Logger
import java.util.logging.SimpleFormatter

object ConsoleLogger {
  def apply(name: String = "metals"): Logger = {
    val logger = Logger.getLogger(name)
    logger.setLevel(Level.ALL)
    val handler = new ConsoleHandler
    handler.setLevel(Level.ALL)
    handler.setFormatter(new SimpleFormatter)
    logger.addHandler(handler)
    logger
  }
}

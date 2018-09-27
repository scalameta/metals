package scala.meta.metals

import scala.meta.jsonrpc.JsonRpcClient
import scala.meta.lsp.Window._
import scribe.Level
import scribe.LogRecord
import scribe.writer.Writer

object LSPLogger extends Writer {
  var notifications: Option[JsonRpcClient] = None
  override def write[M](record: LogRecord[M], output: String): Unit = {
    notifications.foreach { implicit client =>
      record.level match {
        case Level.Error => logMessage.error(record.message)
        case Level.Warn => logMessage.warn(record.message)
        case Level.Info => logMessage.info(record.message)
        case _ => logMessage.log(record.message)
      }
    }
  }
}

package scala.meta.metals

import java.nio.file.Path
import scribe._
import scribe.format._
import scribe.writer.FileWriter

object MetalsLogger {
  def setup(logfile: Path): Unit = {
    val filewriter = FileWriter().path(_ => logfile).autoFlush
    // Example format: "MyProgram.scala:14 trace foo"
    Logger.root
      .clearModifiers()
      .clearHandlers()
      .withHandler(writer = filewriter, formatter = defaultFormat)
      .replace()
  }

  def defaultFormat = formatter"$prettyLevel $message$newLine"
  def debugFormat =
    formatter"$fileName:$line$newLine $prettyLevel$newLine  $message$newLine"
  implicit def AnyLoggable[T]: Loggable[T] = _AnyLoggable
  private val _AnyLoggable = new Loggable[Any] {
    override def apply(value: Any): String =
      value match {
        case s: String =>
          s
        case e: Throwable =>
          Loggable.ThrowableLoggable(e)
        case _ =>
          pprint.PPrinter.Color.tokenize(value).mkString
      }
  }

  private object prettyLevel extends FormatBlock {
    import scribe.Level._
    override def format[M](record: LogRecord[M]): String = {
      val color = record.level match {
        case Trace => Console.MAGENTA
        case Debug => Console.GREEN
        case Info => Console.BLUE
        case Warn => Console.YELLOW
        case Error => Console.RED
        case _ => ""
      }
      color + record.level.namePaddedRight.toLowerCase + Console.RESET
    }
  }

}

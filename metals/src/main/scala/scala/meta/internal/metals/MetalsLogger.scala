package scala.meta.internal.metals

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import scribe._
import scribe.format._
import scribe.writer.FileWriter

object MetalsLogger {
  def updateFormat(): Unit = {
    Logger.root
      .clearHandlers()
      .withHandler(formatter = defaultFormat)
      .replace()
  }
  def redirectSystemOut(logfile: Path): Unit = {
    Files.createDirectories(logfile.getParent)
    val logStream = Files.newOutputStream(logfile)
    val out = new PrintStream(logStream)
    System.setOut(out)
    System.setErr(out)
    setup(logfile)
  }
  def setup(logfile: Path): Unit = {
    val filewriter = FileWriter().path(_ => logfile).autoFlush
    // Example format: "MyProgram.scala:14 trace foo"
    Logger.root
      .clearModifiers()
      .clearHandlers()
      .withHandler(
        writer = filewriter,
        formatter = defaultFormat,
        minimumLevel = Some(Level.Info)
      )
      .withHandler(writer = LanguageClientLogger, minimumLevel = Some(Level.Info))
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

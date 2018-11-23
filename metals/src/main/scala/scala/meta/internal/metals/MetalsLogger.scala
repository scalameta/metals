package scala.meta.internal.metals

import java.io.PrintStream
import java.nio.file.Files
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath
import scribe._
import scribe.format._
import scribe.modify.LogModifier
import scribe.writer.FileWriter

object MetalsLogger {

  val workspaceLogPath = RelativePath(".metals").resolve("metals.log")

  def updateDefaultFormat(): Unit = {
    Logger.root
      .clearHandlers()
      .withHandler(
        formatter = defaultFormat,
        minimumLevel = Some(scribe.Level.Info),
        modifiers = List(MetalsFilter)
      )
      .replace()
  }

  def redirectSystemOut(logfile: AbsolutePath): Unit = {
    Files.createDirectories(logfile.toNIO.getParent)
    val logStream = Files.newOutputStream(logfile.toNIO)
    val out = new PrintStream(logStream)
    System.setOut(out)
    System.setErr(out)
    configureRootLogger(logfile)
  }

  private def configureRootLogger(logfile: AbsolutePath): Unit = {
    Logger.root
      .clearModifiers()
      .clearHandlers()
      .withHandler(
        writer = newFileWriter(logfile),
        formatter = defaultFormat,
        minimumLevel = Some(Level.Info),
        modifiers = List(MetalsFilter)
      )
      .withHandler(
        writer = LanguageClientLogger,
        formatter = MetalsLogger.defaultFormat,
        minimumLevel = Some(Level.Info),
        modifiers = List(MetalsLogger.MetalsFilter)
      )
      .replace()
  }

  object MetalsFilter extends LogModifier {
    override def id = "MetalsFilter"
    override def priority: Priority = Priority.Normal
    override def apply[M](record: LogRecord[M]): Option[LogRecord[M]] = {
      // FIXME: filter on `org.flywaydb` instead of `scribe.sl4j` https://github.com/outr/scribe/issues/100
      if (record.className.startsWith("scribe.slf4j") && record.level < scribe.Level.Warn.value) {
        None
      } else {
        Some(record)
      }
    }

  }

  def setupLspLogger(
      workspace: AbsolutePath,
      redirectSystemStreams: Boolean
  ): Unit = {
    val newLogFile = workspace.resolve(workspaceLogPath)
    scribe.info(s"logging to file $newLogFile")
    if (redirectSystemStreams) {
      redirectSystemOut(newLogFile)
    }
  }

  def newBspLogger(workspace: AbsolutePath): Logger = {
    val logfile = workspace.resolve(workspaceLogPath)
    Logger.root
      .orphan()
      .clearModifiers()
      .clearHandlers()
      .withHandler(
        writer = newFileWriter(logfile),
        formatter = defaultFormat,
        minimumLevel = Some(Level.Info)
      )
  }

  def newFileWriter(logfile: AbsolutePath): FileWriter =
    FileWriter().path(_ => logfile.toNIO).autoFlush

  // Example format: "MyProgram.scala:14 trace foo"
  def defaultFormat =
    formatter"$prettyLevel $message$newLine"
  def debugFormat =
    formatter"$prettyLevel $fileName:$line$newLine$prettyLevel $message$newLine"
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

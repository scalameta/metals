package scala.meta.internal.metals.logging

import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardOpenOption

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import scribe._
import scribe.file.FileWriter
import scribe.file.PathBuilder
import scribe.format.Formatter
import scribe.format.FormatterInterpolator
import scribe.format.date
import scribe.format.levelPaddedRight
import scribe.format.messages
import scribe.modify.LogModifier

object MetalsLogger {

  private val level =
    MetalsServerConfig.default.loglevel match {
      case "debug" => Level.Debug
      case "info" => Level.Info
      case "warn" => Level.Warn
      case "error" => Level.Error
      case "fatal" => Level.Fatal
      case "trace" => Level.Trace
      case _ => Level.Info
    }

  private val workspaceLogPath: RelativePath =
    RelativePath(".metals").resolve("metals.log")

  def updateDefaultFormat(): Unit = {
    Logger.root
      .clearHandlers()
      .withHandler(
        formatter = defaultFormat,
        minimumLevel = Some(level),
        modifiers = List(MetalsFilter()),
      )
      .replace()
  }

  def redirectSystemOut(logfile: AbsolutePath): Unit = {
    Files.createDirectories(logfile.toNIO.getParent)
    val logStream = Files.newOutputStream(
      logfile.toNIO,
      StandardOpenOption.APPEND,
      StandardOpenOption.CREATE,
    )
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
        minimumLevel = Some(level),
        modifiers = List(MetalsFilter()),
      )
      .withHandler(
        writer = LanguageClientLogger,
        formatter = MetalsLogger.defaultFormat,
        minimumLevel = Some(level),
        modifiers = List(MetalsFilter()),
      )
      .replace()
  }

  case class MetalsFilter(id: String = "MetalsFilter") extends LogModifier {
    override def withId(id: String): LogModifier = copy(id = id)
    override def priority: Priority = Priority.Normal
    override def apply(record: LogRecord): Option[LogRecord] = {
      if (
        record.className.startsWith(
          "org.flywaydb"
        ) && record.level < scribe.Level.Warn.value
      ) {
        None
      } else {
        Some(record)
      }
    }

  }

  def setupLspLogger(
      workspace: AbsolutePath,
      redirectSystemStreams: Boolean,
  ): Unit = {
    val newLogFile = workspace.resolve(workspaceLogPath)
    scribe.info(s"logging to file $newLogFile")
    if (redirectSystemStreams) {
      redirectSystemOut(newLogFile)
    }
  }

  def newFileWriter(logfile: AbsolutePath): FileWriter =
    FileWriter(pathBuilder = PathBuilder.static(logfile.toNIO)).flushAlways

  def defaultFormat: Formatter = formatter"$date $levelPaddedRight $messages"

  def silent: LoggerSupport[Unit] =
    new LoggerSupport[Unit] {
      override def log(record: LogRecord): Unit = ()
    }
  def default: LoggerSupport[Unit] = scribe.Logger.root
  def silentInTests: LoggerSupport[Unit] =
    if (MetalsServerConfig.isTesting) silent
    else default
}

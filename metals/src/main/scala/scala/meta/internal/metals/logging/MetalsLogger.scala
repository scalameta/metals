package scala.meta.internal.metals.logging

import java.io.OutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

import scala.util.control.NonFatal

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.TimeFormatter
import scala.meta.internal.metals.utils.LimitedFilesManager
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

  private def logLevel(level: String) =
    level match {
      case "debug" => Level.Debug
      case "info" => Level.Info
      case "warn" => Level.Warn
      case "error" => Level.Error
      case "fatal" => Level.Fatal
      case "trace" => Level.Trace
      case _ => Level.Info
    }
  private val level = logLevel(MetalsServerConfig.default.loglevel)

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

  def redirectSystemOut(logfile: AbsolutePath): Unit = redirectSystemOut(
    List(logfile)
  )

  def redirectSystemOut(logfiles: List[AbsolutePath]): Unit = {
    logfiles.foreach(logfile =>
      Files.createDirectories(logfile.toNIO.getParent)
    )
    val logStreams = logfiles.map(logfile =>
      Files.newOutputStream(
        logfile.toNIO,
        StandardOpenOption.APPEND,
        StandardOpenOption.CREATE,
      )
    )
    val out = new PrintStream(new MutipleOutputsStream(logStreams))
    System.setOut(out)
    System.setErr(out)
    configureRootLogger(logfiles)
  }

  private def configureRootLogger(logfile: List[AbsolutePath]): Unit = {
    logfile
      .foldLeft(
        Logger.root
          .clearModifiers()
          .clearHandlers()
      ) { (logger, logfile) =>
        logger
          .withHandler(
            writer = newFileWriter(logfile),
            formatter = defaultFormat,
            minimumLevel = Some(level),
            modifiers = List(MetalsFilter()),
          )
      }
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
      folders: List[AbsolutePath],
      redirectSystemStreams: Boolean,
      config: MetalsServerConfig,
  ): Unit = {
    val newLogFiles = folders.map(backUpOldLogFileIfTooBig(_, config))
    scribe.info(s"logging to files ${newLogFiles.mkString(",")}")
    if (redirectSystemStreams) {
      redirectSystemOut(newLogFiles)
    } else {
      Logger.root
        .clearModifiers()
        .clearHandlers()
        .withHandler(
          formatter = defaultFormat,
          minimumLevel = Some(logLevel(config.loglevel)),
          modifiers = List(MetalsFilter()),
        )
        .replace()
    }
  }

  private def backupLogsDir(workspaceFolder: AbsolutePath) =
    workspaceFolder.resolve(".metals").resolve(".backup_logs")

  def backUpOldLogFileIfTooBig(
      workspaceFolder: AbsolutePath,
      config: MetalsServerConfig,
  ): AbsolutePath = {
    val logFilePath = workspaceFolder.resolve(workspaceLogPath)
    try {
      if (
        logFilePath.isFile && Files.size(
          logFilePath.toNIO
        ) > config.maxLogFileSize
      ) {
        val backedUpLogFile = backupLogPath(workspaceFolder)
        backedUpLogFile.parent.createDirectories()
        Files.move(
          logFilePath.toNIO,
          backedUpLogFile.toNIO,
          StandardCopyOption.REPLACE_EXISTING,
          StandardCopyOption.ATOMIC_MOVE,
        )
        limitKeptBackupLogs(workspaceFolder, config.maxLogBackups)
      }
    } catch {
      case NonFatal(t) =>
        scribe.warn(s"""|error while creating a backup for log file
                        |$t
                        |""".stripMargin)
    }
    logFilePath
  }

  private def backupLogPath(wokspaceFolder: AbsolutePath): AbsolutePath = {
    val date = TimeFormatter.getDate()
    val time = TimeFormatter.getTime()
    val filename = s"log_${time}"
    backupLogsDir(wokspaceFolder).resolve(date).resolve(filename)
  }

  private def limitKeptBackupLogs(workspaceFolder: AbsolutePath, limit: Int) = {
    val backupDir = backupLogsDir(workspaceFolder)
    new LimitedFilesManager(backupDir.toNIO, limit, "log_".r, "").deleteOld()
  }

  def newFileWriter(logfile: AbsolutePath): FileWriter =
    FileWriter(pathBuilder = PathBuilder.static(logfile.toNIO)).flushAlways

  def defaultFormat: Formatter = formatter"$date $levelPaddedRight $messages"

  def silent: LoggerSupport[Unit] =
    new LoggerSupport[Unit] {
      override def log(record: => LogRecord): Unit = ()
    }
  def default: LoggerSupport[Unit] = scribe.Logger.root
  def silentInTests: LoggerSupport[Unit] =
    if (MetalsServerConfig.isTesting) silent
    else default
}

class MutipleOutputsStream(outputs: List[OutputStream]) extends OutputStream {
  override def write(b: Int): Unit = outputs.foreach(_.write(b))

  override def write(b: Array[Byte], off: Int, len: Int): Unit =
    outputs.foreach(_.write(b, off, len))

  override def flush(): Unit = outputs.foreach(_.flush())

  override def close(): Unit = outputs.foreach(_.close())
}

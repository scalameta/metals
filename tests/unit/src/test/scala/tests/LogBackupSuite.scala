package tests

import java.nio.file.Files

import scala.util.control.NonFatal

import scala.meta.internal.io.PathIO
import scala.meta.internal.metals.Directories
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.RecursivelyDelete
import scala.meta.internal.metals.TimeFormatter
import scala.meta.internal.metals.logging.MetalsLogger
import scala.meta.internal.metals.utils.LimitedFilesManager
import scala.meta.io.AbsolutePath

class LogBackupSuite extends BaseSuite {
  val maxLogBackups = 2
  val serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(maxLogFileSize = 100, maxLogBackups = 2)
  var workspace: AbsolutePath = _
  def log: AbsolutePath = workspace.resolve(Directories.log)
  def backupDir: AbsolutePath =
    workspace.resolve(".metals").resolve(".backup_logs")
  def limitedFilesManager = new LimitedFilesManager(
    backupDir.toNIO,
    maxLogBackups,
    "log_".r,
    "",
  )

  override def beforeEach(context: BeforeEach): Unit = {
    workspace = createWorkspace(context.test.name)
    cleanWorkspace()
  }

  test("too-small-for-backup") {
    log.writeText(".")
    MetalsLogger.backUpOldLogFileIfTooBig(workspace, serverConfig)
    assert(!backupDir.exists)
  }

  test("backup") {
    log.writeText(List.range(1, 100).mkString)
    MetalsLogger.backUpOldLogFileIfTooBig(workspace, serverConfig)
    assert(backupDir.exists)
    assert(limitedFilesManager.getAllFiles().size == 1)
    assert(!log.exists)
    val dirsWithDate = backupDir.toFile.listFiles()
    assert(dirsWithDate.length == 1)
    assert(
      dirsWithDate.forall(d =>
        d.isDirectory() && TimeFormatter.hasDateName(d.getName())
      )
    )
  }

  test("backup-and-delete-overflow") {
    def createBackupLog(text: String) =
      backupDir.resolve(s"log_${System.currentTimeMillis()}").writeText(text)
    val logdata = List.range(1, 100).mkString
    log.writeText(logdata)
    createBackupLog("1")
    Thread.sleep(2)
    createBackupLog("2")
    Thread.sleep(2)
    MetalsLogger.backUpOldLogFileIfTooBig(workspace, serverConfig)
    assertNoDiff(
      limitedFilesManager
        .getAllFiles()
        .sortBy(_.timestamp)
        .flatMap(file => Files.readAllLines(file.toPath).asScala)
        .mkString("\n"),
      s"""|2
          |${logdata}""".stripMargin,
    )
    assert(!log.exists)
  }

  def cleanWorkspace(): Unit =
    if (workspace.isDirectory) {
      try {
        RecursivelyDelete(workspace)
        Files.createDirectories(workspace.toNIO)
      } catch {
        case NonFatal(_) =>
          scribe.warn(s"Unable to delete workspace $workspace")
      }
    }

  protected def createWorkspace(name: String): AbsolutePath = {
    val pathToSuite = PathIO.workingDirectory
      .resolve("target")
      .resolve("e2e")
      .resolve("log-backup")

    val path = pathToSuite.resolve(name)

    Files.createDirectories(path.toNIO)
    path
  }
}

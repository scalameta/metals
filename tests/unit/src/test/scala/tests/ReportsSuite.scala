package tests

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Paths

import scala.meta.internal.metals.FolderReportsZippper
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Report
import scala.meta.internal.metals.StdReportContext
import scala.meta.internal.metals.TimeFormatter
import scala.meta.internal.metals.ZipReportsProvider
import scala.meta.io.AbsolutePath

class ReportsSuite extends BaseSuite {
  val workspace: AbsolutePath = AbsolutePath(Paths.get("."))
  val reportsProvider = new StdReportContext(workspace.toNIO)
  val folderReportsZippper: FolderReportsZippper =
    FolderReportsZippper(exampleBuildTargetsInfo, reportsProvider)

  def exampleBuildTargetsInfo(): List[Map[String, String]] =
    List(
      Map("type" -> "scala 3", "semanticdb" -> Icons.unicode.check),
      Map("type" -> "scala 2", "semanticdb" -> Icons.unicode.check),
    )

  def exampleText(workspaceStr: String = workspace.toString()): String =
    s"""|An error occurred in the file:
        |${workspaceStr}/WrongFile.scala
        |""".stripMargin

  override def afterEach(context: AfterEach): Unit = {
    reportsProvider.deleteAll()
    super.afterEach(context)
  }

  test("create-report") {
    val path =
      reportsProvider.incognito.create(Report("test_error", exampleText()))
    val obtained =
      new String(Files.readAllBytes(path.get), StandardCharsets.UTF_8)
    assertNoDiff(exampleText(StdReportContext.WORKSPACE_STR), obtained)
    assert(reportsProvider.incognito.getReports().length == 1)
    val dirsWithDate =
      reportsProvider.reportsDir.resolve("metals").toFile().listFiles()
    assert(dirsWithDate.length == 1)
    assert(
      dirsWithDate.forall(d =>
        d.isDirectory() && TimeFormatter.hasDateName(d.getName())
      )
    )
  }

  test("delete-old-reports") {
    reportsProvider.incognito.create(
      Report("some_test_error_old", exampleText())
    )
    reportsProvider.incognito.create(
      Report(
        "some_different_test_error_old",
        exampleText(),
      )
    )
    Thread.sleep(2) // to make sure, that the new tests have a later timestamp
    reportsProvider.incognito.create(
      Report("some_test_error_new", exampleText())
    )
    reportsProvider.incognito.create(
      Report(
        "some_different_test_error_new",
        exampleText(),
      )
    )
    val deleted = reportsProvider.incognito.cleanUpOldReports(2)
    deleted match {
      case (_ :: _ :: Nil) if deleted.forall(_.name.contains("old")) =>
      case _ => fail(s"deleted: ${deleted.map(_.name)}")
    }
    val reports = reportsProvider.incognito.getReports()
    reports match {
      case (_ :: _ :: Nil) if reports.forall(_.name.contains("new")) =>
      case _ => fail(s"reports: ${reports.map(_.name)}")
    }
  }

  test("save-with-id") {
    val testId = "test-id"
    val path = reportsProvider.incognito.create(
      Report("test_error", exampleText(), testId)
    )
    val obtained =
      new String(Files.readAllBytes(path.get), StandardCharsets.UTF_8)
    assertNoDiff(
      s"""|id: $testId
          |${exampleText(StdReportContext.WORKSPACE_STR)}""".stripMargin,
      obtained,
    )
    val none1 = reportsProvider.incognito.create(
      Report("test_error_again", exampleText(), testId)
    )
    assert(none1.isEmpty)
    val newReportsProvider = new StdReportContext(workspace.toNIO)
    val none2 = newReportsProvider.incognito.create(
      Report("test_error_again", exampleText(), testId)
    )
    assert(none2.isEmpty)
    val reports = newReportsProvider.incognito.getReports()
    reports match {
      case head :: Nil => assert(head.file.getName == path.get.toFile.getName)
      case _ => fail(s"reports: ${reports.map(_.name)}")
    }
  }

  test("zip-reports") {
    reportsProvider.incognito.create(Report("test_error", exampleText()))
    reportsProvider.incognito.create(
      Report(
        "different_test_error",
        exampleText(),
      )
    )
    val pathToReadMe = ZipReportsProvider.zip(List(folderReportsZippper))
    val zipPath =
      reportsProvider.reportsDir.resolve(StdReportContext.ZIP_FILE_NAME)
    assert(Files.exists(zipPath))
    assert(Files.exists(pathToReadMe.toNIO))
    Files.delete(pathToReadMe.toNIO)
  }
}

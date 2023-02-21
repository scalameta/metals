package scala.meta.internal.metals

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath

class ZipReportsProvider(
    doctorTargetsInfo: () => List[
      Map[String, String]
    ], // we pass the function instead of a whole doctor for the simplicity of testing
    reportContext: StdReportContext,
) {

  def zip(): AbsolutePath = {
    val buildTargersFile = storeBuildTargetsInfo()
    zipReports(List(buildTargersFile))
    crateReportReadme()
  }

  private def crateReportReadme(): AbsolutePath = {
    val path = reportContext.reportsDir.resolve("READ_ME.md")
    if (Files.notExists(path.toNIO)) {
      path.writeText(
        s"""|Please attach `${StdReportContext.ZIP_FILE_NAME}` to your GitHub issue.
            |Reports zip URI: ${reportContext.reportsDir.resolve(StdReportContext.ZIP_FILE_NAME).toURI(false)}
            |""".stripMargin
      )
    }
    path
  }

  private def storeBuildTargetsInfo(): FileToZip = {
    val text = doctorTargetsInfo().zipWithIndex
      .map { case (info, ind) =>
        s"""|#### $ind
            |${info.toList.map { case (key, value) => s"$key: $value" }.mkString("\n")}
            |""".stripMargin
      }
      .mkString("\n")
    FileToZip("build-targets-info.md", text.getBytes())
  }

  private def zipReports(additionalToZip: List[FileToZip]): AbsolutePath = {
    val path = reportContext.reportsDir.resolve(StdReportContext.ZIP_FILE_NAME)
    val zipOut = new ZipOutputStream(Files.newOutputStream(path.toNIO))

    for {
      reportsProvider <- reportContext.allToZip
      report <- reportsProvider.getReports()
    } {
      val zipEntry = new ZipEntry(report.name)
      zipOut.putNextEntry(zipEntry)
      zipOut.write(Files.readAllBytes(report.toPath))
    }

    for {
      toZip <- additionalToZip
    } {
      val zipEntry = new ZipEntry(toZip.name)
      zipOut.putNextEntry(zipEntry)
      zipOut.write(toZip.text)
    }

    zipOut.close()

    path
  }
}

case class FileToZip(name: String, text: Array[Byte])

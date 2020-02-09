package scala.meta.internal.metals

import scala.meta.io.AbsolutePath
import java.net.URI
import scala.concurrent.Future
import MetalsEnrichments._
import java.nio.file.Files
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal

class NewFilesProvider(
    workspace: AbsolutePath,
    packageProvider: PackageProvider
) {

  def createNewFile(directory: Option[URI], name: String, kind: String)(
      implicit ec: ExecutionContext
  ): Future[URI] = kind match {
    case "class" | "object" | "trait" =>
      createClass(directory, name, kind)
    case "package-object" =>
      createPackageObject(directory)
    case "worksheet" =>
      createWorksheet(directory, name)
    case invalid => Future.failed(new IllegalArgumentException(invalid))
  }

  def createWorksheet(directory: Option[URI], name: String)(
      implicit ec: ExecutionContext
  ): Future[URI] = {
    val path = directory
      .fold(workspace)(_.toString.toAbsolutePath)
      .resolve(name + ".worksheet.sc")
    createFile(path)
  }

  def createClass(directory: Option[URI], name: String, kind: String)(
      implicit ec: ExecutionContext
  ): Future[URI] = {
    val path = directory
      .fold(workspace)(_.toString.toAbsolutePath)
      .resolve(name + ".scala")
    val editText =
      packageProvider.packageStatement(path).getOrElse("") +
        classTemplate(kind, name)
    createFileAndWriteText(path, editText)
  }

  def createPackageObject(
      directory: Option[URI]
  )(implicit ec: ExecutionContext): Future[URI] = {
    directory
      .map { directory =>
        val path = directory.toString.toAbsolutePath.resolve("package.scala")
        createFileAndWriteText(
          path,
          packageProvider.packageStatement(path).getOrElse("")
        )
      }
      .getOrElse(
        Future.failed(
          new IllegalArgumentException(
            "'directory' must be provided to create a package object"
          )
        )
      )
  }

  private def createFile(
      path: AbsolutePath
  )(implicit ec: ExecutionContext): Future[URI] = {
    val result = Future {
      Files.createFile(path.toNIO).toUri()
    }
    result.onFailure {
      case NonFatal(e) => scribe.error("Cannot create file", e)
    }
    result
  }

  private def createFileAndWriteText(path: AbsolutePath, text: String)(
      implicit ec: ExecutionContext
  ): Future[URI] = {
    createFile(path).map { newFileUri =>
      path.writeText(text)
      newFileUri
    }
  }

  private def classTemplate(kind: String, name: String): String =
    s"""|$kind $name {
        |  
        |}
        |""".stripMargin

}

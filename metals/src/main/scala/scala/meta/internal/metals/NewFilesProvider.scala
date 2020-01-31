package scala.meta.internal.metals

import scala.meta.io.AbsolutePath
import java.net.URI
import scala.concurrent.Future
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.Position
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
  ): Future[(URI, Option[WorkspaceEdit])] = kind match {
    case "class" | "object" | "trait" =>
      createClass(directory, name, kind).map {
        case (path, edit) => (path, Some(edit))
      }
    case "worksheet" =>
      createWorksheet(directory, name).map((_, None))
    case _ => Future.failed(new IllegalArgumentException)
  }

  def createWorksheet(directory: Option[URI], name: String)(
      implicit ec: ExecutionContext
  ): Future[URI] = {
    val path = directory
      .fold(workspace)(_.toString.toAbsolutePath)
      .resolve(name + ".worksheet.sc")
    val result = Future {
      Files.createFile(path.toNIO).toUri()
    }
    result.onFailure {
      case NonFatal(e) => scribe.error("Cannot create worksheet", e)
    }
    result
  }

  def createClass(directory: Option[URI], name: String, kind: String)(
      implicit ec: ExecutionContext
  ): Future[(URI, WorkspaceEdit)] = {
    val path = directory
      .fold(workspace)(_.toString.toAbsolutePath)
      .resolve(name + ".scala")
    val result = Future {
      val newFileUri = Files.createFile(path.toNIO).toUri()
      val editText =
        packageProvider.packageStatement(path).getOrElse("") +
          classTemplate(kind, name)

      val edit =
        new WorkspaceEdit(
          Map(
            newFileUri.toString() ->
              List(
                new TextEdit(
                  new Range(new Position(0, 0), new Position(0, 0)),
                  editText
                )
              ).asJava
          ).asJava
        )
      (newFileUri, edit)
    }
    result.onFailure {
      case NonFatal(e) => scribe.error("Cannot create file", e)
    }
    result
  }

  private def classTemplate(kind: String, name: String): String =
    s"""|$kind $name {
        |
        |}
        |""".stripMargin

}

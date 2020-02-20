package scala.meta.internal.metals

import scala.meta.io.AbsolutePath
import java.net.URI
import scala.concurrent.Future
import MetalsEnrichments._
import java.nio.file.Files
import scala.concurrent.ExecutionContext
import scala.util.control.NonFatal
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Range
import scala.meta.internal.metals.Messages.NewScalaFile

class NewFilesProvider(
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    packageProvider: PackageProvider,
    serverConfig: MetalsServerConfig,
    focusedDocument: () => Option[AbsolutePath]
)(
    implicit ec: ExecutionContext
) {

  private val classPick = MetalsPickItem(id = "class", label = "Class")
  private val objectPick = MetalsPickItem(id = "object", label = "Object")
  private val traitPick = MetalsPickItem(id = "trait", label = "Trait")
  private val packageObjectPick =
    MetalsPickItem(id = "package-object", label = "Package Object")
  private val worksheetPick =
    MetalsPickItem(id = "worksheet", label = "Worksheet")

  def createNewFileDialog(directoryUri: Option[URI]): Future[Unit] = {
    val directory = directoryUri
      .map(_.toString.toAbsolutePath)
      .orElse(focusedDocument().map(_.parent))

    val newlyCreatedFile =
      askForKind
        .flatMapOption {
          case kind @ (classPick.id | objectPick.id | traitPick.id) =>
            askForName(kind)
              .mapOption(
                createClass(directory, _, kind)
              )
          case worksheetPick.id =>
            askForName(worksheetPick.id)
              .mapOption(
                createWorksheet(directory, _)
              )
          case packageObjectPick.id =>
            createPackageObject(directory).liftOption
          case invalid =>
            Future.failed(new IllegalArgumentException(invalid))
        }

    newlyCreatedFile.map {
      case Some(path) =>
        openFile(path)
      case None => ()
    }
  }

  private def askForKind: Future[Option[String]] = {
    client
      .metalsPickInput(
        MetalsPickInputParams(
          List(
            classPick,
            objectPick,
            traitPick,
            packageObjectPick,
            worksheetPick
          ).asJava,
          placeHolder = NewScalaFile.selectTheKindOfFileMessage
        )
      )
      .asScala
      .map {
        case kind if !kind.cancelled => Some(kind.itemId)
        case _ => None
      }
  }

  private def askForName(kind: String): Future[Option[String]] = {
    client
      .metalsInputBox(
        MetalsInputBoxParams(prompt = NewScalaFile.enterNameMessage(kind))
      )
      .asScala
      .map {
        case name if !name.cancelled => Some(name.value)
        case _ => None
      }
  }

  private def createClass(
      directory: Option[AbsolutePath],
      name: String,
      kind: String
  ): Future[AbsolutePath] = {
    val path = directory.getOrElse(workspace).resolve(name + ".scala")
    val editText =
      packageProvider.packageStatement(path).getOrElse("") +
        classTemplate(kind, name)
    createFileAndWriteText(path, editText)
  }

  private def createPackageObject(
      directory: Option[AbsolutePath]
  ): Future[AbsolutePath] = {
    directory
      .map { directory =>
        val path = directory.resolve("package.scala")
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

  private def createWorksheet(
      directory: Option[AbsolutePath],
      name: String
  ): Future[AbsolutePath] = {
    val path = directory.getOrElse(workspace).resolve(name + ".worksheet.sc")
    createFile(path)
  }

  private def createFile(
      path: AbsolutePath
  ): Future[AbsolutePath] = {
    val result = Future {
      AbsolutePath(
        Files.createFile(path.toNIO)
      )
    }
    result.onFailure {
      case NonFatal(e) =>
        scribe.error("Cannot create file", e)
        client.showMessage(
          MessageType.Error,
          s"Cannot create file:\n ${e.toString()}"
        )
    }
    result
  }

  private def createFileAndWriteText(
      path: AbsolutePath,
      text: String
  ): Future[AbsolutePath] = {
    createFile(path).map { _ =>
      path.writeText(text)
      path
    }
  }

  private def openFile(path: AbsolutePath): Unit = {
    client.metalsExecuteClientCommand(
      new ExecuteCommandParams(
        ClientCommands.GotoLocation.id,
        List(
          new Location(path.toURI.toString(), new Range()): Object
        ).asJava
      )
    )
  }

  private def classTemplate(kind: String, name: String): String =
    s"""|$kind $name {
        |  
        |}
        |""".stripMargin

}

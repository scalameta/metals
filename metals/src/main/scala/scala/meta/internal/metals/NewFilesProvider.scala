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
import DialogEnrichments._
import scala.meta.internal.metals.Messages.NewScalaFile

class NewFilesProvider(
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    packageProvider: PackageProvider,
    serverConfig: MetalsServerConfig,
    focusedDocument: => Option[AbsolutePath]
) {

  private val classPick = MetalsPickItem(id = "class", label = "Class")
  private val objectPick = MetalsPickItem(id = "object", label = "Object")
  private val traitPick = MetalsPickItem(id = "trait", label = "Trait")
  private val packageObjectPick =
    MetalsPickItem(id = "package-object", label = "Package Object")
  private val worksheetPick =
    MetalsPickItem(id = "worksheet", label = "Worksheet")

  def createNewFileDialog(directoryUri: Option[URI])(
      implicit ec: ExecutionContext
  ): Future[Unit] = {
    val directory = directoryUri
      .map(_.toString.toAbsolutePath)
      .orElse(focusedDocument.map(_.parent))

    val newlyCreatedFile =
      askForKind
        .continueWith {
          case kind @ (classPick.id | objectPick.id | traitPick.id) =>
            askForName(kind)
              .endWith(
                createClass(directory, _, kind)
              )
          case worksheetPick.id =>
            askForName(worksheetPick.id)
              .endWith(
                createWorksheet(directory, _)
              )
          case packageObjectPick.id =>
            endWith(
              createPackageObject(directory)
            )
          case invalid =>
            Future.failed(new IllegalArgumentException(invalid))
        }

    newlyCreatedFile.map {
      case Some(path) =>
        openFile(path)
      case None => ()
    }
  }

  private def askForKind(
      implicit ec: ExecutionContext
  ): Future[Option[String]] = {
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

  private def askForName(kind: String)(
      implicit ec: ExecutionContext
  ): Future[Option[String]] = {
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
  )(
      implicit ec: ExecutionContext
  ): Future[AbsolutePath] = {
    val path = directory.getOrElse(workspace).resolve(name + ".scala")
    val editText =
      packageProvider.packageStatement(path).getOrElse("") +
        classTemplate(kind, name)
    createFileAndWriteText(path, editText)
  }

  private def createPackageObject(
      directory: Option[AbsolutePath]
  )(implicit ec: ExecutionContext): Future[AbsolutePath] = {
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

  private def createWorksheet(directory: Option[AbsolutePath], name: String)(
      implicit ec: ExecutionContext
  ): Future[AbsolutePath] = {
    val path = directory.getOrElse(workspace).resolve(name + ".worksheet.sc")
    createFile(path)
  }

  private def createFile(
      path: AbsolutePath
  )(implicit ec: ExecutionContext): Future[AbsolutePath] = {
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

  private def createFileAndWriteText(path: AbsolutePath, text: String)(
      implicit ec: ExecutionContext
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
          new Location(path.toString(), new org.eclipse.lsp4j.Range()): Object
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

object DialogEnrichments {

  implicit class DialogContinuation[A](state: Future[Option[A]]) {

    def continueWith[B](
        continuation: A => Future[Option[B]]
    )(implicit ec: ExecutionContext): Future[Option[B]] =
      state.flatMap(_.fold(Future.successful(Option.empty[B]))(continuation))

    def endWith[B](
        ending: A => Future[B]
    )(implicit ec: ExecutionContext): Future[Option[B]] =
      state.flatMap(
        _.fold(Future.successful(Option.empty[B]))(ending(_).map(Some(_)))
      )

  }

  def endWith[A](ending: Future[A])(
      implicit ec: ExecutionContext
  ): Future[Option[A]] = ending.map(Some(_))

}

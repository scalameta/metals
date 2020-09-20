package scala.meta.internal.metals.newScalaFile

import java.net.URI
import java.nio.file.FileAlreadyExistsException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.control.NonFatal

import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsInputBoxParams
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsQuickPickParams
import scala.meta.internal.metals.PackageProvider
import scala.meta.internal.metals.newScalaFile.NewFileTypes._
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Range

class NewFileProvider(
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    packageProvider: PackageProvider,
    focusedDocument: () => Option[AbsolutePath]
)(implicit
    ec: ExecutionContext
) {

  def handleFileCreation(
      directoryUri: Option[URI],
      name: Option[String],
      fileType: Option[String]
  ): Future[Unit] = {
    val directory = directoryUri
      .map { uri =>
        val path = uri.toString.toAbsolutePath
        if (path.isFile)
          path.parent
        else
          path
      }
      .orElse(focusedDocument().map(_.parent))

    val newlyCreatedFile = {
      fileType.flatMap(getFromString) match {
        case Some(ft) => createFile(directory, ft, name)
        case None =>
          askForKind
            .flatMapOption(createFile(directory, _, name))
      }
    }

    newlyCreatedFile.map {
      case Some((path, cursorRange)) =>
        openFile(path, cursorRange)
      case None => ()
    }
  }

  private def createFile(
      directory: Option[AbsolutePath],
      fileType: NewFileType,
      name: Option[String]
  ) = {
    fileType match {
      case kind @ (Class | CaseClass | Object | Trait) =>
        getName(kind, name)
          .mapOption(
            createClass(directory, _, kind)
          )
      case Worksheet =>
        getName(Worksheet, name)
          .mapOption(
            createEmptyFile(directory, _, ".worksheet.sc")
          )
      case AmmoniteScript =>
        getName(AmmoniteScript, name)
          .mapOption(
            createEmptyFile(directory, _, ".sc")
          )
      case PackageObject =>
        createPackageObject(directory).liftOption
    }
  }

  private def askForKind: Future[Option[NewFileType]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          List(
            Class.toQuickPickItem,
            CaseClass.toQuickPickItem,
            Object.toQuickPickItem,
            Trait.toQuickPickItem,
            PackageObject.toQuickPickItem,
            Worksheet.toQuickPickItem,
            AmmoniteScript.toQuickPickItem
          ).asJava,
          placeHolder = NewScalaFile.selectTheKindOfFileMessage
        )
      )
      .asScala
      .map {
        case kind if !kind.cancelled => getFromString(kind.itemId)
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

  private def getName(
      kind: NewFileType,
      name: Option[String]
  ): Future[Option[String]] = {
    name match {
      case Some(v) if v.trim.length > 0 => Future.successful(name)
      case _ => askForName(kind.label)
    }
  }

  private def createClass(
      directory: Option[AbsolutePath],
      name: String,
      kind: NewFileType
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + ".scala")
    //name can be actually be "foo/Name", where "foo" is a folder to create
    val className = directory.getOrElse(workspace).resolve(name).filename
    val template = kind match {
      case CaseClass => caseClassTemplate(className)
      case _ => classTemplate(kind.id, className)
    }
    val editText = template.map { s =>
      packageProvider
        .packageStatement(path)
        .map(_.fileContent)
        .getOrElse("") + s
    }
    createFileAndWriteText(path, editText)
  }

  private def createPackageObject(
      directory: Option[AbsolutePath]
  ): Future[(AbsolutePath, Range)] = {
    directory
      .map { directory =>
        val path = directory.resolve("package.scala")
        createFileAndWriteText(
          path,
          packageProvider
            .packageStatement(path)
            .getOrElse(NewFileTemplate.empty)
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

  private def createEmptyFile(
      directory: Option[AbsolutePath],
      name: String,
      extension: String
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + extension)
    createFileAndWriteText(path, NewFileTemplate.empty)
  }

  private def createFileAndWriteText(
      path: AbsolutePath,
      template: NewFileTemplate
  ): Future[(AbsolutePath, Range)] = {
    val result = if (path.exists) {
      Future.failed(
        new FileAlreadyExistsException(s"The file $path already exists.")
      )
    } else {
      Future {
        path.writeText(template.fileContent)
        (path, template.cursorPosition.toLSP)
      }
    }
    result.failed.foreach {
      case NonFatal(e) => {
        scribe.error("Cannot create file", e)
        client.showMessage(
          MessageType.Error,
          s"Cannot create file:\n ${e.toString()}"
        )
      }
    }
    result
  }

  private def openFile(path: AbsolutePath, cursorRange: Range): Unit = {
    client.metalsExecuteClientCommand(
      new ExecuteCommandParams(
        ClientCommands.GotoLocation.id,
        List(
          new Location(path.toURI.toString(), cursorRange): Object
        ).asJava
      )
    )
  }

  private def classTemplate(kind: String, name: String): NewFileTemplate = {
    val indent = "  "
    NewFileTemplate(s"""|$kind $name {
                        |$indent@@
                        |}
                        |""".stripMargin)
  }

  private def caseClassTemplate(name: String): NewFileTemplate =
    NewFileTemplate(s"final case class $name(@@)")

}

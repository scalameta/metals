package scala.meta.internal.metals

import scala.meta.io.AbsolutePath
import java.net.URI
import scala.concurrent.Future
import MetalsEnrichments._
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
    focusedDocument: () => Option[AbsolutePath]
)(
    implicit ec: ExecutionContext
) {

  private val classPick = MetalsQuickPickItem(id = "class", label = "Class")
  private val caseClassPick =
    MetalsQuickPickItem(id = "case-class", label = "Case class")
  private val objectPick = MetalsQuickPickItem(id = "object", label = "Object")
  private val traitPick = MetalsQuickPickItem(id = "trait", label = "Trait")
  private val packageObjectPick =
    MetalsQuickPickItem(id = "package-object", label = "Package Object")
  private val worksheetPick =
    MetalsQuickPickItem(id = "worksheet", label = "Worksheet")

  def createNewFileDialog(
      directoryUri: Option[URI],
      name: Option[String]
  ): Future[Unit] = {
    val directory = directoryUri
      .map(_.toString.toAbsolutePath)
      .orElse(focusedDocument().map(_.parent))

    val newlyCreatedFile =
      askForKind
        .flatMapOption {
          case kind @ (classPick.id | caseClassPick.id | objectPick.id |
              traitPick.id) =>
            getName(kind, name)
              .mapOption(
                createClass(directory, _, kind)
              )
          case worksheetPick.id =>
            getName(worksheetPick.id, name)
              .mapOption(
                createWorksheet(directory, _)
              )
          case packageObjectPick.id =>
            createPackageObject(directory).liftOption
          case invalid =>
            Future.failed(new IllegalArgumentException(invalid))
        }

    newlyCreatedFile.map {
      case Some((path, cursorRange)) =>
        openFile(path, cursorRange)
      case None => ()
    }
  }

  private def askForKind: Future[Option[String]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          List(
            classPick,
            caseClassPick,
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

  private def getName(
      kind: String,
      name: Option[String]
  ): Future[Option[String]] = {
    name match {
      case Some(v) if v.trim.length > 0 => Future.successful(name)
      case _ => askForName(kind)
    }
  }

  private def createClass(
      directory: Option[AbsolutePath],
      name: String,
      kind: String
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + ".scala")
    //name can be actually be "foo/Name", where "foo" is a folder to create
    val className = directory.getOrElse(workspace).resolve(name).filename
    val template = kind match {
      case caseClassPick.id => caseClassTemplate(className)
      case _ => classTemplate(kind, className)
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

  private def createWorksheet(
      directory: Option[AbsolutePath],
      name: String
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + ".worksheet.sc")
    createFile(path).map((_, new Range))
  }

  private def createFile(
      path: AbsolutePath
  ): Future[AbsolutePath] = {
    val result = Future {
      path.touch()
      path
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
      template: NewFileTemplate
  ): Future[(AbsolutePath, Range)] = {
    createFile(path).map { _ =>
      path.writeText(template.fileContent)
      (path, template.cursorPosition.toLSP)
    }
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

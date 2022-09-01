package scala.meta.internal.metals.newScalaFile

import java.net.URI
import java.nio.file.FileAlreadyExistsException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.PackageProvider
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.newScalaFile.NewFileTypes._
import scala.meta.internal.pc.Identifier
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.MessageType
import org.eclipse.lsp4j.Range

class NewFileProvider(
    workspace: AbsolutePath,
    client: MetalsLanguageClient,
    packageProvider: PackageProvider,
    focusedDocument: () => Option[AbsolutePath],
    selector: ScalaVersionSelector,
)(implicit
    ec: ExecutionContext
) {

  def handleFileCreation(
      directoryUri: Option[URI],
      name: Option[String],
      fileType: Option[String],
      isScala: Boolean,
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
          val askForKind =
            if (isScala)
              askForScalaKind(
                directory.forall(dir =>
                  ScalaVersions.isScala3Version(
                    selector.scalaVersionForPath(dir)
                  )
                )
              )
            else askForJavaKind
          askForKind.flatMapOption(createFile(directory, _, name))
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
      name: Option[String],
  ) = {
    fileType match {
      case kind @ (Class | CaseClass | Object | Trait | Enum) =>
        getName(kind, name)
          .mapOption(
            createClass(directory, _, kind, ".scala")
          )
      case kind @ (JavaClass | JavaEnum | JavaInterface | JavaRecord) =>
        getName(kind, name)
          .mapOption(
            createClass(directory, _, kind, ".java")
          )
      case ScalaFile =>
        getName(ScalaFile, name).mapOption(
          createEmptyFileWithPackage(directory, _)
        )
      case Worksheet =>
        getName(Worksheet, name)
          .mapOption(
            createEmptyFile(directory, _, ".worksheet.sc")
          )
      case ScalaScript =>
        getName(ScalaScript, name)
          .mapOption(
            createEmptyFile(directory, _, ".sc")
          )
      case PackageObject =>
        createPackageObject(directory).liftOption
    }
  }

  private def askForKind(
      kinds: List[NewFileType]
  ): Future[Option[NewFileType]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          kinds.map(_.toQuickPickItem).asJava,
          placeHolder = NewScalaFile.selectTheKindOfFileMessage,
        )
      )
      .asScala
      .flatMapOptionInside(kind => getFromString(kind.itemId))
  }

  private def askForScalaKind(
      isScala3: Boolean
  ): Future[Option[NewFileType]] = {
    val allFileTypes = List(
      ScalaFile,
      Class,
      CaseClass,
      Object,
      Trait,
      PackageObject,
      Worksheet,
      ScalaScript,
    )
    val withEnum =
      if (isScala3) allFileTypes :+ Enum else allFileTypes
    askForKind(withEnum)
  }

  private def askForJavaKind: Future[Option[NewFileType]] = {
    val allFileTypes = List(
      JavaClass,
      JavaInterface,
      JavaEnum,
    )
    val withRecord =
      if (Properties.isJavaAtLeast("14")) allFileTypes :+ JavaRecord
      else allFileTypes
    askForKind(withRecord)
  }

  private def askForName(kind: String): Future[Option[String]] = {
    client
      .metalsInputBox(
        MetalsInputBoxParams(prompt = NewScalaFile.enterNameMessage(kind))
      )
      .asScala
      .mapOptionInside(_.value)
  }

  private def getName(
      kind: NewFileType,
      name: Option[String],
  ): Future[Option[String]] = {
    name match {
      case Some(v) if v.trim.length > 0 => Future.successful(name)
      case _ => askForName(kind.label)
    }
  }

  private def createClass(
      directory: Option[AbsolutePath],
      name: String,
      kind: NewFileType,
      ext: String,
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + ext)
    // name can be actually be "foo/Name", where "foo" is a folder to create
    val className = Identifier.backtickWrap(
      directory.getOrElse(workspace).resolve(name).filename
    )
    val template = kind match {
      case CaseClass => caseClassTemplate(className)
      case Enum => enumTemplate(className)
      case JavaRecord => javaRecordTemplate(className)
      case _ => classTemplate(kind.syntax.getOrElse(""), className)
    }
    val editText = template.map { s =>
      packageProvider
        .packageStatement(path)
        .map(_.fileContent)
        .getOrElse("") + s
    }
    createFileAndWriteText(path, editText)
  }

  private def createEmptyFileWithPackage(
      directory: Option[AbsolutePath],
      name: String,
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + ".scala")
    val pkg = packageProvider
      .packageStatement(path)
      .map(_.fileContent)
      .getOrElse("")
    val template = s"""|$pkg@@
                       |""".stripMargin
    createFileAndWriteText(path, NewFileTemplate(template))
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
            .getOrElse(NewFileTemplate.empty),
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
      extension: String,
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.getOrElse(workspace).resolve(name + extension)
    createFileAndWriteText(path, NewFileTemplate.empty)
  }

  private def createFileAndWriteText(
      path: AbsolutePath,
      template: NewFileTemplate,
  ): Future[(AbsolutePath, Range)] = {
    val result = if (path.exists) {
      Future.failed(
        new FileAlreadyExistsException(s"The file $path already exists.")
      )
    } else {
      Future {
        path.writeText(template.fileContent)
        (path, template.cursorPosition.toLsp)
      }
    }
    result.failed.foreach {
      case NonFatal(e) => {
        scribe.error("Cannot create file", e)
        client.showMessage(
          MessageType.Error,
          s"Cannot create file:\n ${e.toString()}",
        )
      }
    }
    result
  }

  private def openFile(path: AbsolutePath, cursorRange: Range): Unit = {
    val location = new Location(path.toURI.toString(), cursorRange)
    client.metalsExecuteClientCommand(
      ClientCommands.GotoLocation.toExecuteCommandParams(
        ClientCommands.WindowLocation(location.getUri(), location.getRange())
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

  private def enumTemplate(name: String): NewFileTemplate = {
    val indent = "  "
    NewFileTemplate(s"""|enum $name {
                        |${indent}case@@
                        |}
                        |""".stripMargin)
  }

  private def javaRecordTemplate(name: String): NewFileTemplate = {
    NewFileTemplate(s"""|record $name(@@) {
                        |
                        |}
                        |""".stripMargin)
  }

  private def caseClassTemplate(name: String): NewFileTemplate =
    NewFileTemplate(s"""|final case class $name(@@)
                        |""".stripMargin)

}

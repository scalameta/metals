package scala.meta.internal.metals.newScalaFile

import java.net.URI
import java.nio.file.FileAlreadyExistsException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Icons
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
    client: MetalsLanguageClient,
    packageProvider: PackageProvider,
    selector: ScalaVersionSelector,
    icons: Icons,
    isReadClipboardProvider: Boolean,
    onCreate: AbsolutePath => Future[Unit],
)(implicit
    ec: ExecutionContext
) {

  def handleFileCreation(
      directoryUri: Option[URI],
      name: Option[String],
      fileType: Option[String],
      isScala: Boolean,
  ): Future[Unit] =
    for {
      optDirectory <- directoryUri
        .map { uri =>
          val path = uri.toString.toAbsolutePath
          if (path.isFile)
            path.parent
          else
            path
        }
        .map(x => Future.successful(Some(x)))
        .getOrElse(NewProjectProvider.askForPath(None, icons, client))
      _ <- optDirectory
        .map { directory =>
          val newlyCreatedFile = {
            fileType.flatMap(getFromString) match {
              case Some(ft) => createFile(directory, ft, name)
              case None =>
                val askForKind =
                  if (isScala)
                    askForScalaKind(
                      ScalaVersions.isScala3Version(
                        selector.scalaVersionForPath(directory)
                      )
                    )
                  else askForJavaKind
                askForKind.flatMapOption(createFile(directory, _, name))
            }
          }

          newlyCreatedFile.map {
            case Some((path, cursorRange)) =>
              onCreate(path)
              openFile(path, cursorRange)
            case None => ()
          }
        }
        .getOrElse {
          scribe.warn("Cannot create file, no directory provided.")
          Future.successful(())
        }
    } yield ()

  private def createFile(
      directory: AbsolutePath,
      fileType: NewFileType,
      name: Option[String],
  ) = {
    fileType match {
      case kind @ (Class | CaseClass | Object | Trait | Enum) =>
        getName(kind, name, None)
          .mapOption(
            createClass(directory, _, kind, ".scala")
          )
      case kind @ (JavaClass | JavaEnum | JavaInterface | JavaRecord) =>
        getName(kind, name, None)
          .mapOption(
            createClass(directory, _, kind, ".java")
          )
      case ScalaFile =>
        getName(ScalaFile, name, None).mapOption(
          createEmptyFileWithPackage(directory, _)
        )
      case Worksheet =>
        getName(Worksheet, name, None)
          .mapOption(
            createEmptyFile(directory, _, ".worksheet.sc")
          )
      case ScalaScript =>
        getName(ScalaScript, name, None)
          .mapOption(
            createEmptyFile(directory, _, ".sc")
          )
      case FromClipboard =>
        createFromClipboard(directory, name)
      case PackageObject =>
        createPackageObject(directory).liftOption
    }
  }

  /** Suggests a filename from Scala snippet content (first class/object/trait/enum name). */
  private def suggestNameFromScalaContent(content: String): String = {
    val pattern =
      """(?m)^\s*(?:(?:sealed|final|implicit|abstract)\s+)*(?:case\s+)?(?:class|object|trait|enum)\s+([A-Za-z_][A-Za-z0-9_]*)""".r
    pattern.findFirstMatchIn(content).map(_.group(1)).getOrElse("Snippet")
  }

  private def createFromClipboard(
      directory: AbsolutePath,
      name: Option[String],
  ): Future[Option[(AbsolutePath, Range)]] =
    client.metalsReadClipboard().asScala.flatMap {
      case Some(content) if content.trim.nonEmpty =>
        val suggestedName = suggestNameFromScalaContent(content)
        getName(FromClipboard, name, Some(suggestedName))
          .mapOption { fileName =>
            val path =
              directory.resolve(fileNameWithExtension(fileName, ".scala"))
            val text = packageProvider
              .packageStatement(path)
              .map(_.fileContent)
              .getOrElse("") + content.trim + "\n"
            createFileAndWriteText(path, NewFileTemplate.fromContent(text))
          }
      case _ =>
        client.showMessage(
          MessageType.Info,
          "Clipboard is empty or not supported by this client.",
        )
        Future.successful(None)
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
    val baseTypes = List(
      ScalaFile,
      Class,
      CaseClass,
      Object,
      Trait,
      PackageObject,
      Worksheet,
      ScalaScript,
    )
    val withClipboard =
      if (isReadClipboardProvider) FromClipboard +: baseTypes else baseTypes
    val withEnum =
      if (isScala3) withClipboard :+ Enum else withClipboard
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

  private def askForName(
      kind: String,
      value: Option[String],
  ): Future[Option[String]] = {
    client
      .metalsInputBox(
        MetalsInputBoxParams(
          prompt = NewScalaFile.enterNameMessage(kind),
          value = value.orNull,
        )
      )
      .asScala
      .mapOptionInside(_.value)
  }

  private def getName(
      kind: NewFileType,
      name: Option[String],
      suggestedValue: Option[String],
  ): Future[Option[String]] = {
    name match {
      case Some(v) if v.trim.length > 0 => Future.successful(name)
      case _ => askForName(kind.label, suggestedValue)
    }
  }

  /** Avoids double extension when user types "Foo.scala" or "Foo.java". */
  private def fileNameWithExtension(name: String, ext: String): String =
    if (name.endsWith(ext)) name else name + ext

  private def createClass(
      directory: AbsolutePath,
      name: String,
      kind: NewFileType,
      ext: String,
  ): Future[(AbsolutePath, Range)] = {
    val fileName = fileNameWithExtension(name, ext)
    val path = directory.resolve(fileName)
    // name can be "foo/Name" or "Foo.scala"; use path filename without ext for template
    val className = Identifier.backtickWrap(path.filename.stripSuffix(ext))
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
      directory: AbsolutePath,
      name: String,
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.resolve(fileNameWithExtension(name, ".scala"))
    val pkg = packageProvider
      .packageStatement(path)
      .map(_.fileContent)
      .getOrElse("")
    val template = s"""|$pkg@@
                       |""".stripMargin
    createFileAndWriteText(path, NewFileTemplate(template))
  }

  private def createPackageObject(
      directory: AbsolutePath
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.resolve("package.scala")
    createFileAndWriteText(
      path,
      packageProvider
        .packageStatement(path)
        .getOrElse(NewFileTemplate.empty),
    )
  }

  private def createEmptyFile(
      directory: AbsolutePath,
      name: String,
      extension: String,
  ): Future[(AbsolutePath, Range)] = {
    val path = directory.resolve(fileNameWithExtension(name, extension))
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

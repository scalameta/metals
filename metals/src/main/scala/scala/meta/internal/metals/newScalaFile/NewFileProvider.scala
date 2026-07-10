package scala.meta.internal.metals.newScalaFile

import java.net.URI
import java.nio.file.FileAlreadyExistsException

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Properties
import scala.util.control.NonFatal

import scala.meta.internal.builds.NewProjectProvider
import scala.meta.internal.metals.BuildTargets
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Messages.NewScalaFile
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.NewFilesBracelessSyntax
import scala.meta.internal.metals.PackageProvider
import scala.meta.internal.metals.ScalaVersionSelector
import scala.meta.internal.metals.ScalaVersions
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.metals.newScalaFile.NewFileTypes._
import scala.meta.internal.parsing.Trees
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
    buildTargets: BuildTargets,
    trees: Trees,
    userConfig: () => UserConfiguration,
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
    val useBraceless = useBracelessSyntax(path)
    val template = kind match {
      case CaseClass => caseClassTemplate(className)
      case Enum => enumTemplate(className, useBraceless)
      case JavaRecord => javaRecordTemplate(className)
      case _ =>
        classTemplate(kind.syntax.getOrElse(""), className, useBraceless)
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
        .packageStatement(path, braceless = useBracelessSyntax(path))
        .getOrElse(NewFileTemplate.empty),
    )
  }

  /**
   * Whether generated code for `path` should use Scala 3's optional-braces
   * (significant-indentation) syntax instead of curly braces.
   *
   * Only Scala 3 supports braceless syntax, and the compiler can still forbid
   * it (`-no-indent`, `-old-syntax`, migration mode) — those cases always use
   * braces, overriding the user's preference. Otherwise the
   * `new-files-braceless-syntax` setting decides: `always`/`never` force the
   * choice, while `auto` (the default) matches the style of nearby existing
   * sources — the target package directory and its enclosing directories up to
   * the source root — then the `-indent` scalac option, and finally braces.
   */
  private def useBracelessSyntax(path: AbsolutePath): Boolean =
    path.isScalaFilename &&
      ScalaVersions.isScala3Version(selector.scalaVersionForPath(path)) && {
        val options = scalacOptions(path)
        significantIndentationAllowed(options) && {
          userConfig().newFilesBracelessSyntax match {
            case NewFilesBracelessSyntax.Always => true
            case NewFilesBracelessSyntax.Never => false
            case NewFilesBracelessSyntax.Auto =>
              styleFromExistingSources(path)
                .orElse(indentationPreferredByScalac(options))
                .getOrElse(false)
          }
        }
      }

  /**
   * Braceless preference inferred from an existing source file, if any.
   *
   * Directories are searched nearest-first (the file's own package directory,
   * then each enclosing directory up to the source root), and each directory's
   * own `.scala` files are inspected in a stable, filename-sorted order. The
   * search is lazy and stops at the first file that yields a determinate style,
   * so it neither depends on filesystem ordering nor walks the whole tree.
   */
  private def styleFromExistingSources(
      path: AbsolutePath
  ): Option[Boolean] =
    directoriesNearestFirst(
      path.parent,
      buildTargets.inverseSourceItem(path),
    ).iterator
      .flatMap(styleInDirectory(_, exclude = path))
      .nextOption()

  private def styleInDirectory(
      directory: AbsolutePath,
      exclude: AbsolutePath,
  ): Option[Boolean] =
    directory.list.toList
      .filter(file => file.isScalaFilename && file != exclude)
      .sortBy(_.filename)
      .iterator
      .flatMap(trees.get)
      .flatMap(BracelessSyntax.prefersBraceless)
      .nextOption()

  /** `directory` followed by its ancestors up to and including `sourceRoot`. */
  private def directoriesNearestFirst(
      directory: AbsolutePath,
      sourceRoot: Option[AbsolutePath],
  ): List[AbsolutePath] =
    sourceRoot match {
      case Some(root) if directory.toNIO.startsWith(root.toNIO) =>
        @tailrec
        def loop(
            current: AbsolutePath,
            acc: List[AbsolutePath],
        ): List[AbsolutePath] = {
          val next = current :: acc
          if (current == root) next.reverse
          else loop(current.parent, next)
        }
        loop(directory, Nil)
      case _ => List(directory)
    }

  private def scalacOptions(path: AbsolutePath): List[String] =
    buildTargets
      .inverseSources(path)
      .flatMap(buildTargets.scalaTarget)
      .map(_.options)
      .getOrElse(Nil)

  /**
   * Whether the compiler allows significant indentation. `-no-indent`,
   * `-old-syntax` and `-source:<v>-migration` all require classical braces.
   */
  private def significantIndentationAllowed(
      options: List[String]
  ): Boolean =
    !options.exists { option =>
      option == "-no-indent" || option == "-old-syntax" ||
      option.endsWith("-migration")
    }

  /** Braceless preference from an explicit `-indent` scalac option, if any. */
  private def indentationPreferredByScalac(
      options: List[String]
  ): Option[Boolean] =
    if (options.contains("-indent")) Some(true) else None

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

  private def classTemplate(
      kind: String,
      name: String,
      braceless: Boolean,
  ): NewFileTemplate = {
    val indent = "  "
    // A bodyless declaration is the braceless equivalent of an empty class/trait/
    // object: `class Foo:` with an empty indented region is a parse error, whereas
    // `class Foo` compiles. The cursor lands after the name, ready for a body.
    if (braceless)
      NewFileTemplate(s"""|$kind $name@@
                          |""".stripMargin)
    else
      NewFileTemplate(s"""|$kind $name {
                          |$indent@@
                          |}
                          |""".stripMargin)
  }

  private def enumTemplate(
      name: String,
      braceless: Boolean,
  ): NewFileTemplate = {
    val indent = "  "
    if (braceless)
      NewFileTemplate(s"""|enum $name:
                          |${indent}case@@
                          |""".stripMargin)
    else
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

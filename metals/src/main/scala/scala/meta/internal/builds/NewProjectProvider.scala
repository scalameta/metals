package scala.meta.internal.builds

import java.io.File

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.util.Try
import scala.util.matching.Regex

import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.Icons
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.StatusBar
import scala.meta.internal.metals.clients.language.MetalsInputBoxParams
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.clients.language.MetalsOpenWindowParams
import scala.meta.internal.metals.clients.language.MetalsQuickPickItem
import scala.meta.internal.metals.clients.language.MetalsQuickPickParams
import scala.meta.internal.process.ExitCodes
import scala.meta.io.AbsolutePath

import coursierapi._

class NewProjectProvider(
    client: MetalsLanguageClient,
    statusBar: StatusBar,
    config: ClientConfiguration,
    shell: ShellRunner,
    icons: Icons,
    workspace: AbsolutePath,
)(implicit context: ExecutionContext) {

  private val templatesUrl =
    "https://github.com/foundweekends/giter8/wiki/giter8-templates.md"
  private val giterDependency = Dependency
    .of("org.foundweekends.giter8", "giter8_2.12", "0.13.0-M1")
  // equal to cmd's: g8 playframework/play-scala-seed.g8 --name=../<<name>>
  private val giterMain = "giter8.Giter8"

  private var allTemplates = Seq.empty[MetalsQuickPickItem]
  def allTemplatesFromWeb: Seq[MetalsQuickPickItem] =
    synchronized {
      if (allTemplates.nonEmpty) {
        allTemplates
      } else {
        statusBar.trackBlockingTask(
          "Fetching template information from Github"
        ) {
          // Matches:
          // - [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
          // (A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager)
          val all = for {
            result <- Try(requests.get(templatesUrl)).toOption.toIterable
            _ = if (result.statusCode != 200)
              client.showMessage(
                NewScalaProject.templateDownloadFailed(result.statusMessage)
              )
            if result.statusCode == 200
          } yield {
            NewProjectProvider.templatesFromText(result.text(), icons.github)
          }
          allTemplates = all.flatten.toSeq
        }
      }
      NewProjectProvider.back +: allTemplates.toSeq
    }

  def createNewProjectFromTemplate(): Future[Unit] = {
    val base = workspace.parent
    val withTemplate = askForTemplate(
      NewProjectProvider.curatedTemplates(icons)
    )
    withTemplate
      .flatMapOption { template =>
        askForPath(Some(base)).mapOptionInside { path => (template, path) }
      }
      .flatMapOption { case (template, path) =>
        askForName(nameFromPath(template.id), NewScalaProject.enterName)
          .map { name => Some((template, path, name)) }
      }
      .flatMap {
        case Some((template, inputPath, Some(projectName))) =>
          createNewProject(
            inputPath,
            template.label.replace(s"${icons.github}", ""),
            projectName,
          )
        // It's fine to just return if the user resigned
        case _ => Future.successful(())
      }
  }

  private def createNewProject(
      inputPath: AbsolutePath,
      template: String,
      projectName: String,
  ): Future[Unit] = {
    val projectPath = inputPath.resolve(projectName.toLowerCase())
    val parent = projectPath.parent
    projectPath.createDirectories()
    val command = List(
      template,
      s"--name=${projectPath.filename}",
    )
    shell
      .runJava(
        giterDependency,
        giterMain,
        parent,
        command,
      )
      .flatMap {
        case ExitCodes.Success =>
          askForWindow(projectPath)
        case _ =>
          Future.successful {
            client.showMessage(
              NewScalaProject
                .creationFailed(template, parent.toString())
            )
          }
      }
  }

  private def askForWindow(projectPath: AbsolutePath): Future[Unit] = {
    def openWindow(newWindow: Boolean) = {
      val params = MetalsOpenWindowParams(
        projectPath.toURI.toString(),
        java.lang.Boolean.valueOf(newWindow),
      )
      val command = ClientCommands.OpenFolder.toExecuteCommandParams(params)
      client.metalsExecuteClientCommand(command)
    }

    if (config.isOpenNewWindowProvider()) {
      client
        .showMessageRequest(NewScalaProject.askForNewWindowParams())
        .asScala
        .map {
          case msg if msg == NewScalaProject.no =>
            openWindow(newWindow = false)
          case msg if msg == NewScalaProject.yes =>
            openWindow(newWindow = true)
          case _ =>
        }
    } else {
      Future.successful {
        client.showMessage(NewScalaProject.newProjectCreated(projectPath))
      }
    }
  }

  private def askForTemplate(
      templates: Seq[MetalsQuickPickItem]
  ): Future[Option[MetalsQuickPickItem]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          templates.asJava,
          placeHolder = NewScalaProject.selectTheTemplate,
        )
      )
      .asScala
      .flatMapOption {
        case kind if kind.itemId == NewProjectProvider.more.id =>
          askForTemplate(allTemplatesFromWeb)
        case kind if kind.itemId == NewProjectProvider.back.id =>
          askForTemplate(NewProjectProvider.curatedTemplates(icons))
        case kind if kind.itemId == NewProjectProvider.custom.id =>
          askForName("", NewScalaProject.enterG8Template)
            .mapOptionInside { g8Path =>
              MetalsQuickPickItem(
                nameFromPath(g8Path),
                g8Path,
                NewProjectProvider.custom.description,
              )
            }
        case kind =>
          Future.successful(
            templates
              .find(_.id == kind.itemId)
          )
      }
  }

  private def askForName(
      default: String,
      prompt: String,
  ): Future[Option[String]] = {
    if (config.isInputBoxEnabled()) {
      client
        .metalsInputBox(
          MetalsInputBoxParams(
            prompt = prompt,
            value = default,
          )
        )
        .asScala
        .flatMapOption {
          case name if name.value.nonEmpty =>
            Future.successful(Some(name.value))
          // reask if empty
          case _ => askForName(default, prompt)
        }
    } else {
      Future.successful(Some(default))
    }
  }

  private def askForPath(
      from: Option[AbsolutePath]
  ): Future[Option[AbsolutePath]] = {

    def quickPickDir(filename: String) = {
      MetalsQuickPickItem(
        id = filename,
        label = s"${icons.folder} $filename",
      )
    }

    val paths = from match {
      case Some(nonRootPath) =>
        nonRootPath.list.toList.collect {
          case path if path.isDirectory =>
            quickPickDir(path.filename)
        }
      case None =>
        File.listRoots.map(file => quickPickDir(file.toString())).toList
    }
    val currentDir =
      MetalsQuickPickItem(id = "ok", label = s"${icons.check} Ok")
    val parentDir =
      MetalsQuickPickItem(id = "..", label = s"${icons.folder} ..")
    val includeUpAndCurrent =
      if (from.isDefined) List(currentDir, parentDir) else Nil
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          (includeUpAndCurrent ::: paths).asJava,
          placeHolder = from.map(_.toString()).getOrElse(""),
        )
      )
      .asScala
      .flatMapOption {
        case path if path.itemId == currentDir.id =>
          Future.successful(from)
        case path if path.itemId == parentDir.id =>
          askForPath(from.flatMap(_.parentOpt))
        case path =>
          from match {
            case Some(nonRootPath) =>
              askForPath(Some(nonRootPath.resolve(path.itemId)))
            case None =>
              val newRoot = File
                .listRoots()
                .collect {
                  case root if root.toString() == path.itemId =>
                    AbsolutePath(root.toPath())
                }
                .headOption
              askForPath(newRoot)
          }

      }
  }

  // scala/hello-world.g8 -> hello-world
  private def nameFromPath(g8Path: String) = {
    g8Path.replaceAll(".*/", "").replace(".g8", "")
  }
}

object NewProjectProvider {

  val custom: MetalsQuickPickItem = MetalsQuickPickItem(
    id = "custom",
    label = "Custom",
    description = "Enter template manually",
  )

  val more: MetalsQuickPickItem = MetalsQuickPickItem(
    id = "more",
    label = "Discover more...",
    description = "From github.com/foundweekends/giter8/wiki/giter8-templates",
  )

  val back: MetalsQuickPickItem = MetalsQuickPickItem(
    id = "back",
    label = "Back",
    description = "Back to curated Metals templates",
  )

  def curatedTemplates(icons: Icons): Seq[MetalsQuickPickItem] = {
    Seq(
      MetalsQuickPickItem(
        id = "scala/hello-world.g8",
        label = "scala/hello-world.g8",
        description = "A template to demonstrate a minimal Scala application",
      ),
      MetalsQuickPickItem(
        id = "scala/scalatest-example.g8",
        label = "scala/scalatest-example.g8",
        description = "A template for trying out ScalaTest",
      ),
      MetalsQuickPickItem(
        id = "akka/akka-scala-seed.g8",
        label = "akka/akka-scala-seed.g8",
        description = "A minimal seed template for an Akka with Scala build",
      ),
      MetalsQuickPickItem(
        id = "zio/zio-project-seed.g8",
        label = "zio/zio-project-seed.g8",
        description = "A template for ZIO",
      ),
      MetalsQuickPickItem(
        id = "playframework/play-scala-seed.g8",
        label = "playframework/play-scala-seed.g8",
        description = "Play Scala Seed Template",
      ),
      MetalsQuickPickItem(
        id = "lagom/lagom-scala.g8",
        label = "lagom/lagom-scala.g8",
        description = "A Lagom Scala seed template for sbt",
      ),
      MetalsQuickPickItem(
        id = "scala-native/scala-native.g8",
        label = "scala-native/scala-native.g8",
        description = "Scala Native",
      ),
      MetalsQuickPickItem(
        id = "scala/scala3.g8",
        label = "scala/scala3.g8",
        description = "A template for trying out Scala 3",
      ),
      MetalsQuickPickItem(
        id = "http4s/http4s.g8",
        label = "http4s/http4s.g8",
        description = "Simple http4s example",
      ),
      MetalsQuickPickItem(
        id = "com-lihaoyi/mill-scala-hello.g8",
        label = "com-lihaoyi/mill-scala-hello.g8",
        description = "A Scala template for the Mill build tool",
      ),
      MetalsQuickPickItem(
        id = "scalameta/gradle-scala-seed.g8",
        label = "scalameta/gradle-scala-seed.g8",
        description = "A Scala template for the Gradle build tool",
      ),
      MetalsQuickPickItem(
        id = "scalameta/maven-scala-seed.g8",
        label = "scalameta/maven-scala-seed.g8",
        description = "A Scala template for the Maven build tool",
      ),
      MetalsQuickPickItem(
        id = "VirtusLab/akka-http-kubernetes.g8",
        label = "VirtusLab/akka-http-kubernetes.g8",
        description = "Akka HTTP application using Kubernetes",
      ),
    ).map { item =>
      item.copy(label = s"${icons.github}" + item.label)
    } ++ Seq(custom, more)

  }

  private val templatePattern: Regex = {
    val markdownLink = """- \[([^\[]+)\]\s*\([^\(]+\)"""
    val whitespacesWithSingleNewline = """[ \t]*\r?\n?[ \t]*"""
    val optionalDescription = """\(?([^\n]*)\)?"""
    s"${markdownLink}${whitespacesWithSingleNewline}${optionalDescription}".r
  }

  def templatesFromText(
      text: String,
      icon: String,
  ): List[MetalsQuickPickItem] = {
    NewProjectProvider.templatePattern
      .findAllIn(text)
      .matchData
      .toList
      .collect {
        case matching if matching.groupCount == 2 =>
          MetalsQuickPickItem(
            id = matching.group(1),
            label = icon + matching.group(1),
            description =
              matching.group(2).trim.stripPrefix("(").stripSuffix(")"),
          )
      }
  }
}

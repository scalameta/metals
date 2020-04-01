package scala.meta.internal.builds

import scala.meta.io.AbsolutePath
import scala.meta.internal.metals.MetalsLanguageClient
import scala.meta.internal.metals.MetalsQuickPickItem
import scala.concurrent.Future
import scala.meta.internal.metals.MetalsQuickPickParams
import scala.meta.internal.metals.MetalsEnrichments._
import scala.concurrent.ExecutionContext
import scala.meta.internal.metals.MetalsInputBoxParams
import org.eclipse.lsp4j.ExecuteCommandParams
import scala.meta.internal.metals.UserConfiguration
import scala.meta.internal.metals.ClientCommands
import java.net.URI
import scala.util.Try
import scala.meta.internal.metals.StatusBar
import coursierapi._
import scala.meta.internal.metals.Time
import scala.meta.internal.metals.Messages._
import scala.meta.internal.metals.MetalsOpenWindowParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.Icons

class NewProjectProvider(
    buildTools: BuildTools,
    client: MetalsLanguageClient,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration,
    time: Time,
    shell: ShellRunner,
    icons: Icons
)(implicit context: ExecutionContext) {

  private val templatesUrl =
    "https://github.com/foundweekends/giter8/wiki/giter8-templates.md"
  private val gitterDependency = Dependency
    .of("org.foundweekends.giter8", "giter8_2.12", "0.12.0")
  // equal to cmd's: g8 playframework/play-scala-seed.g8 --name=../<<name>>
  private val gitterMain = "giter8.Giter8"

  lazy val allTemplatesFromWeb: Seq[MetalsQuickPickItem] = {
    statusBar.trackBlockingTask("Fetching template information from Github") {
      // Matches:
      // - [jimschubert/finatra.g8](https://github.com/jimschubert/finatra.g8)
      //(A simple Finatra 2.5 template with sbt-revolver and sbt-native-packager)
      val pattern = """\[(.+)\]\s*\(.+\)\s*\((.+)\)""".r
      val all = for {
        result <- Try(requests.get(templatesUrl)).toOption.toIterable
        if result.statusCode == 200
      } yield {
        pattern.findAllIn(result.text).matchData.toList.collect {
          case matching if matching.groupCount == 2 =>
            MetalsQuickPickItem(
              id = matching.group(1),
              label = s"${icons.github} " + matching.group(1),
              description = matching.group(2)
            )
        }
      }
      NewProjectProvider.back +: all.flatten.toSeq
    }
  }

  def checkNew(existingDirectory: Option[AbsolutePath]): Future[Boolean] = {
    val base = AbsolutePath(System.getProperty("user.home"))
    val withTemplate = askForTemplate(
      NewProjectProvider.defaultTemplates(icons)
    )
    val fullySpecified = if (existingDirectory.isDefined) {
      withTemplate.mapOption { template =>
        val Some(directory) = existingDirectory
        Future.successful(
          (template, directory.parent, Some(directory.filename))
        )
      }
    } else {
      withTemplate
        .flatMapOption { template =>
          constructPath(base).mapOptionInside { path => (template, path) }
        }
        .flatMapOption {
          case (template, path) =>
            askForName(nameFromPath(template.id), NewScalaProject.enterName)
              .map { name => Some((template, path, name)) }
        }
    }

    fullySpecified.flatMap {
      case Some((template, inputPath, Some(projectName))) =>
        createNewProject(
          inputPath,
          template.label.replace(s"${icons.github} ", ""),
          projectName,
          existingDirectory.isDefined
        )
      // It's fine to just return if the user resigned
      case _ => Future.successful(false)
    }
  }

  private def createNewProject(
      inputPath: AbsolutePath,
      template: String,
      projectName: String,
      createdLocally: Boolean
  ): Future[Boolean] = {
    val projectPath = inputPath.resolve(projectName)
    val parent = projectPath.parent
    projectPath.createDirectories()
    val command = List(
      template,
      s"--name=${projectPath.filename}"
    )
    shell
      .runJava(
        gitterDependency,
        gitterMain,
        parent,
        command
      )
      .flatMap {
        case result if result == 0 =>
          if (!createdLocally) {
            askForWindow(projectPath.toURI).map(_ => false)
          } else {
            Future.successful(true)
          }
        case _ =>
          Future.successful {
            client.showMessage(
              NewScalaProject
                .creationFailed(template, parent.toString())
            )
            false
          }
      }
  }

  private def askForWindow(uri: URI): Future[Unit] = {
    def openWindow(newWindow: Boolean) = {
      val params = MetalsOpenWindowParams(
        uri.toString(),
        new java.lang.Boolean(newWindow)
      )
      val command = new ExecuteCommandParams(
        ClientCommands.OpenWindow.id,
        List[Object](
          params.toJsonObject
        ).asJava
      )
      client.metalsExecuteClientCommand(command)
    }
    client
      .showMessageRequest(NewScalaProject.askForNewWindowParams())
      .asScala
      .map {
        case msg if msg == NewScalaProject.no =>
          openWindow(newWindow = true)
        case msg if msg == NewScalaProject.yes =>
          openWindow(newWindow = false)
        case _ =>
      }
  }

  private def askForTemplate(
      templates: Seq[MetalsQuickPickItem]
  ): Future[Option[MetalsQuickPickItem]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          templates.asJava,
          placeHolder = NewScalaProject.selectTheTemplate
        )
      )
      .asScala
      .flatMap {
        case kind if kind.itemId == NewProjectProvider.more.id =>
          askForTemplate(allTemplatesFromWeb)
        case kind if kind.itemId == NewProjectProvider.back.id =>
          askForTemplate(NewProjectProvider.defaultTemplates(icons))
        case kind if kind.itemId == NewProjectProvider.custom.id =>
          askForName("", NewScalaProject.enterG8Template)
            .mapOptionInside { g8Path =>
              MetalsQuickPickItem(
                nameFromPath(g8Path),
                g8Path,
                NewProjectProvider.custom.description
              )
            }
        case kind if !kind.cancelled =>
          Future.successful(
            templates
              .find(_.id == kind.itemId)
          )
        case _ => Future.successful(None)
      }
  }

  private def askForName(
      default: String,
      prompt: String
  ): Future[Option[String]] = {
    client
      .metalsInputBox(
        MetalsInputBoxParams(
          prompt = prompt,
          value = default
        )
      )
      .asScala
      .flatMap {
        case name if !name.cancelled && name.value.nonEmpty =>
          Future.successful(Some(name.value))
        case name if name.cancelled => Future.successful(None)
        // reask if empty
        case _ => askForName(default, prompt)
      }
  }

  private def constructPath(
      from: AbsolutePath
  ): Future[Option[AbsolutePath]] = {
    val paths = from.list.toList
      .collect {
        case path if path.isDirectory =>
          MetalsQuickPickItem(
            id = path.filename,
            label = s"${icons.folder} ${path.filename}"
          )
      }
    val currentDir =
      MetalsQuickPickItem(id = "ok", label = s"${icons.check} Ok")
    val parentDir =
      MetalsQuickPickItem(id = "..", label = s"${icons.folder} ..")
    val includeUp = if (from.hasParent) List(parentDir) else Nil
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          (currentDir :: includeUp ::: paths).asJava,
          placeHolder = from.toString()
        )
      )
      .asScala
      .flatMap {
        case path if path.cancelled => Future.successful(None)
        case path if path.itemId == currentDir.id =>
          Future.successful(Some(from))
        case path if path.itemId == parentDir.id =>
          constructPath(from.parent)
        case path =>
          constructPath(from.resolve(path.itemId))
      }
  }

  // scala/hello-world.g8 -> hello-world
  private def nameFromPath(g8Path: String) = {
    g8Path.replaceAll(".*/", "").replace(".g8", "")
  }
}

object NewProjectProvider {

  val custom = MetalsQuickPickItem(
    id = "custom",
    label = "Custom",
    description = "Enter template manually"
  )

  val more = MetalsQuickPickItem(
    id = "more",
    label = "Discover more...",
    description = "From github.com/foundweekends/giter8/wiki/giter8-templates"
  )

  val back = MetalsQuickPickItem(
    id = "back",
    label = "Back",
    description = "Back to curated Metals templates"
  )

  // TODO - templates for maven/gradle/mill
  def defaultTemplates(icons: Icons) = {
    Seq(
      MetalsQuickPickItem(
        id = "scala/hello-world.g8",
        label = "scala/hello-world.g8",
        description = "A template to demonstrate a minimal Scala application"
      ),
      MetalsQuickPickItem(
        id = "scala/scalatest-example.g8",
        label = "scala/scalatest-example.g8",
        description = "A template for trying out ScalaTest"
      ),
      MetalsQuickPickItem(
        id = "akka/akka-scala-seed.g8",
        label = "akka/akka-scala-seed.g8",
        description = "A minimal seed template for an Akka with Scala build"
      ),
      MetalsQuickPickItem(
        id = "zio/zio-project-seed.g8",
        label = "zio/zio-project-seed.g8",
        description = "A template for ZIO"
      ),
      MetalsQuickPickItem(
        id = "playframework/play-scala-seed.g8",
        label = "playframework/play-scala-seed.g8",
        description = "Play Scala Seed Template"
      ),
      MetalsQuickPickItem(
        id = "lagom/lagom-scala.g8",
        label = "lagom/lagom-scala.g8",
        description = "A Lagom Scala seed template for sbt"
      ),
      MetalsQuickPickItem(
        id = "scala-native/scala-native.g8",
        label = "scala-native/scala-native.g8",
        description = "Scala Native"
      ),
      MetalsQuickPickItem(
        id = "lampepfl/dotty.g8",
        label = "lampepfl/dotty.g8",
        description = "A template for trying out Dotty"
      ),
      MetalsQuickPickItem(
        id = "http4s/http4s.g8",
        label = "http4s/http4s.g8",
        description = "http4s services"
      )
    ).map { item =>
      item.copy(label = s"${icons.github} " + item.label)
    } ++ Seq(custom, more)

  }

}

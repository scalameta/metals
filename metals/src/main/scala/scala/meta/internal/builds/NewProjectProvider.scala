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
import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsOpenWindowParams
import scala.meta.internal.metals.JsonParser._

class NewProjectProvider(
    buildTools: BuildTools,
    client: MetalsLanguageClient,
    statusBar: StatusBar,
    userConfig: () => UserConfiguration,
    time: Time,
    messages: Messages,
    shell: ShellRunner
)(implicit context: ExecutionContext) {

  // TODO ask if not build tool

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
            val id = nameFromPath(matching.group(1))
            MetalsQuickPickItem(
              id = id,
              label = matching.group(1),
              description = matching.group(2)
            )
        }
      }
      NewProjectProvider.back +: all.flatten.toSeq
    }
  }

  def checkNew(): Future[Unit] = {
    val base = AbsolutePath(System.getProperty("user.home"))
    askForTemplate(NewProjectProvider.defaultTemplates)
      .flatMapOption { template =>
        constructPath(base).mapOptionInside { path => (template, path) }
      }
      .flatMapOption {
        case (template, path) =>
          askForName(template.id, messages.NewScalaProject.enterName).map {
            name => Some((template, path, name))
          }
      }
      .flatMap {
        case Some((template, inputPath, Some(projectName))) =>
          createNewProject(inputPath, template.label, projectName)
        // It's fine to just return if the user resigned
        case _ => Future.successful(())
      }
  }

  private def createNewProject(
      inputPath: AbsolutePath,
      template: String,
      projectName: String
  ) = {
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
          askForWindow(projectPath.toURI)
        case _ =>
          Future.successful {
            client.showMessage(
              messages.NewScalaProject
                .creationFailed(template, parent.toString())
            )
          }
      }
  }

  // TODO use proper class instead of separate params
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
      .showMessageRequest(messages.NewScalaProject.askForNewWindowParams())
      .asScala
      .map {
        case msg if msg == messages.NewScalaProject.yes =>
          openWindow(newWindow = true)
        case msg if msg == messages.NewScalaProject.no =>
          openWindow(newWindow = false)
      }
  }

  private def askForTemplate(
      templates: Seq[MetalsQuickPickItem]
  ): Future[Option[MetalsQuickPickItem]] = {
    client
      .metalsQuickPick(
        MetalsQuickPickParams(
          templates.asJava,
          placeHolder = messages.NewScalaProject.selectTheTemplate
        )
      )
      .asScala
      .flatMap {
        case kind if kind.itemId == NewProjectProvider.more.id =>
          askForTemplate(allTemplatesFromWeb)
        case kind if kind.itemId == NewProjectProvider.back.id =>
          askForTemplate(NewProjectProvider.defaultTemplates)
        case kind if kind.itemId == NewProjectProvider.custom.id =>
          askForName("", messages.NewScalaProject.enterG8Template)
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
      pompt: String
  ): Future[Option[String]] = {
    client
      .metalsInputBox(
        MetalsInputBoxParams(
          prompt = pompt,
          value = default
        )
      )
      .asScala
      .flatMap {
        case name if !name.cancelled && name.value.nonEmpty =>
          Future.successful(Some(name.value))
        case name if name.cancelled => Future.successful(None)
        // reask if empty
        case _ => askForName(default, pompt)
      }
  }

  private def constructPath(
      from: AbsolutePath
  ): Future[Option[AbsolutePath]] = {
    val paths = from.list.toList
      .map(_.filename)
      .map(path => MetalsQuickPickItem(id = path, label = path))
    val currentDir = MetalsQuickPickItem(id = ".", label = ".")
    val parentDir = MetalsQuickPickItem(id = "..", label = "..")
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
  val defaultTemplates = Seq(
    MetalsQuickPickItem(
      id = "hello-world",
      label = "scala/hello-world.g8",
      description = "A template to demonstrate a minimal Scala application"
    ),
    MetalsQuickPickItem(
      id = "hello-world",
      label = "scala/scalatest-example.g8",
      description = "A template for trying out ScalaTest"
    ),
    MetalsQuickPickItem(
      id = "akka-scala-seed",
      label = "akka/akka-scala-seed.g8",
      description = "A minimal seed template for an Akka with Scala build"
    ),
    MetalsQuickPickItem(
      id = "zio-project-seed",
      label = "zio/zio-project-seed.g8",
      description = "A template for ZIO"
    ),
    MetalsQuickPickItem(
      id = "play-scala-seed",
      label = "playframework/play-scala-seed.g8",
      description = "Play Scala Seed Template"
    ),
    MetalsQuickPickItem(
      id = "lagom-scala",
      label = "lagom/lagom-scala.g8",
      description = "A Lagom Scala seed template for sbt"
    ),
    MetalsQuickPickItem(
      id = "scala-native",
      label = "scala-native/scala-native.g8",
      description = "Scala Native"
    ),
    // Uncomment once Scala 3 support is merged
    // MetalsQuickPickItem(
    //   id = "dotty",
    //   label = "lampepfl/dotty.g8",
    //   description = "A template for trying out Dotty"
    // ),
    MetalsQuickPickItem(
      id = "http4s",
      label = "http4s/http4s.g8",
      description = "http4s services"
    ),
    custom,
    more
  )

}

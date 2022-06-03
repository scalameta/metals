package scala.meta.internal.metals.doctor

import java.util.concurrent.atomic.AtomicBoolean

import scala.meta.internal.metals.BuildInfo
import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientConfiguration
import scala.meta.internal.metals.HtmlBuilder
import scala.meta.internal.metals.MetalsHttpServer
import scala.meta.internal.metals.ParametrizedCommand
import scala.meta.internal.metals.Urls
import scala.meta.internal.metals.clients.language.MetalsLanguageClient
import scala.meta.internal.metals.config.DoctorFormat

class HeadDoctor(
    doctors: () => List[Doctor],
    httpServer: () => Option[MetalsHttpServer],
    clientConfig: ClientConfiguration,
    languageClient: MetalsLanguageClient,
) {
  private val isVisible = new AtomicBoolean(false)

  def onVisibilityDidChange(newState: Boolean): Unit = {
    isVisible.set(newState)
  }

  /**
   * Returns a full HTML page for the HTTP client.
   */
  def problemsHtmlPage(url: String): String = {
    val livereload = Urls.livereload(url)
    HtmlBuilder()
      .page(
        doctorTitle,
        List(livereload, HtmlBuilder.htmlCSS),
        HtmlBuilder.bodyStyle,
      ) { html =>
        html.section("Build targets", buildTargetsTable)
      }
      .render
  }

  /**
   * Executes the "Run doctor" server command.
   */
  def executeRunDoctor(): Unit = {
    onVisibilityDidChange(true)
    executeDoctor(
      clientCommand = ClientCommands.RunDoctor,
      onServer = server => {
        Urls.openBrowser(server.address + "/doctor")
      },
    )
  }

  def executeRefreshDoctor(): Unit =
    executeDoctor(
      clientCommand = ClientCommands.ReloadDoctor,
      onServer = server => {
        server.reload()
      },
    )

  /**
   * @param clientCommand RunDoctor or ReloadDoctor
   * @param onServer piece of logic that will be executed when http server is enabled
   */
  private def executeDoctor(
      clientCommand: ParametrizedCommand[String],
      onServer: MetalsHttpServer => Unit,
  ): Unit = {
    val isVisibilityProvider = clientConfig.isDoctorVisibilityProvider()
    val shouldDisplay = isVisibilityProvider && isVisible.get()
    if (shouldDisplay || !isVisibilityProvider) {
      if (
        clientConfig.isExecuteClientCommandProvider() && !clientConfig
          .isHttpEnabled()
      ) {
        val output = clientConfig.doctorFormat() match {
          case DoctorFormat.Json => buildTargetsJson()
          case DoctorFormat.Html => buildTargetsHtml()
        }
        val params = clientCommand.toExecuteCommandParams(output)
        languageClient.metalsExecuteClientCommand(params)
      } else {
        httpServer() match {
          case Some(server) =>
            onServer(server)
          case None =>
            scribe.warn(
              "Unable to run doctor. Make sure `isHttpEnabled` is set to `true`."
            )
        }
      }
    }
  }

  private def buildTargetsHtml(): String =
    new HtmlBuilder()
      .element("h1")(_.text(doctorTitle))
      .call(buildTargetsTable)
      .render

  private def buildTargetsTable(html: HtmlBuilder): Unit = {
    val jdkInfo = getJdkInfo()

    jdkInfo.foreach { jdkMsg =>
      html.element("p") { builder =>
        builder.bold(jdkVersionTitle)
        builder.text(jdkMsg)
      }
    }

    html.element("p") { builder =>
      builder.bold(serverVersionTitle)
      builder.text(BuildInfo.metalsVersion)
    }

    html.element("p") {
      _.text(buildTargetDescription)
    }
    val includeWorkspaceFolderName = areMultipleWorkspaceFolders
    doctors().foreach(_.buildTargetsTable(html, includeWorkspaceFolderName))
  }

  private def buildTargetsJson(): String = {
    val results = doctors().map(_.buildTargetsJson())
    val jdkInfo = getJdkInfo().map(info => s"$jdkVersionTitle$info")
    val serverInfo = s"$serverVersionTitle${BuildInfo.metalsVersion}"
    val header = DoctorHeader(jdkInfo, serverInfo, buildTargetDescription)
    val result =
      DoctorResults(
        doctorTitle,
        header,
        results,
      ).toJson
    ujson.write(result)
  }

  private def getJdkInfo(): Option[String] =
    for {
      version <- Option(System.getProperty("java.version"))
      vendor <- Option(System.getProperty("java.vendor"))
      home <- Option(System.getProperty("java.home"))
    } yield s"$version from $vendor located at $home"

  private def areMultipleWorkspaceFolders = doctors().length > 1

  private val doctorTitle = "Metals Doctor"
  private val jdkVersionTitle = "Metals Java: "
  private val serverVersionTitle = "Metals Server version: "
  private def buildTargetDescription =
    "Below are listed the build targets " +
      (if (areMultipleWorkspaceFolders) "for each workspace folder. "
       else "for this workspace. ") +
      "One build target corresponds to one classpath. For example, normally one sbt project maps to " +
      "two build targets: main and test."
}

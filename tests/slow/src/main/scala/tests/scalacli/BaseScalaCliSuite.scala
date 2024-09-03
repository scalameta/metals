package tests.scalacli

import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeoutException

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

import scala.meta.internal.metals.Messages
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.scalacli.ScalaCli

import ch.epfl.scala.bsp4j.MessageType
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageActionItem
import tests.BaseLspSuite
import tests.ScriptsAssertions

abstract class BaseScalaCliSuite(protected val scalaVersion: String)
    extends BaseLspSuite(s"scala-cli-$scalaVersion")
    with ScriptsAssertions {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(loglevel = "debug")

  override def beforeEach(context: BeforeEach): Unit = {
    super.beforeEach(context)
    dapClient.touch()
    dapServer.touch()
    bspTrace.touch()
  }

  private val scheduler = Executors.newSingleThreadScheduledExecutor()

  private def timeout(
      message: String,
      duration: FiniteDuration,
  ): Future[Unit] = {
    val p = Promise[Unit]()
    val r: Runnable = { () =>
      p.failure(new TimeoutException(message))
    }
    scheduler.schedule(r, duration.length, duration.unit)
    p.future
  }

  override def afterAll(): Unit = {
    super.afterAll()
    scheduler.shutdown()
  }

  override def munitIgnore: Boolean =
    !isValidScalaVersionForEnv(scalaVersion)

  private var importedPromise = Promise[Unit]()

  override def newServer(workspaceName: String): Unit = {
    super.newServer(workspaceName)
    val previousShowMessageHandler = server.client.showMessageHandler
    server.client.showMessageHandler = { params =>
      if (params == Messages.ImportScalaScript.ImportedScalaCli)
        importedPromise.success(())
      else if (
        params.getType == MessageType.ERROR && params.getMessage.startsWith(
          "Error importing Scala CLI project "
        )
      )
        importedPromise.failure(
          new Exception(s"Error importing project: $params")
        )
      else
        previousShowMessageHandler(params)
    }
    val previousShowMessageRequestHandler =
      server.client.showMessageRequestHandler
    server.client.showMessageRequestHandler = { params =>
      def useBsp = Files.exists(
        server.server.path
          .resolve(".bsp/scala-cli.json")
          .toNIO
      )
      if (params == Messages.ImportScalaScript.params())
        Some(
          new MessageActionItem(
            if (useBsp)
              Messages.ImportScalaScript.dismiss
            else
              Messages.ImportScalaScript.doImportScalaCli
          )
        )
      else if (params == Messages.ImportAllScripts.params())
        Some(
          new MessageActionItem(
            if (useBsp)
              Messages.ImportAllScripts.dismiss
            else
              Messages.ImportAllScripts.importAll
          )
        )
      else
        previousShowMessageRequestHandler(params)
    }
  }

  protected def manualLayout: String =
    s"""/metals.json
       |{
       |  "a": {
       |    "scalaVersion": "$scalaVersion"
       |  }
       |}
       |
       |""".stripMargin

  protected def bspLayout: String =
    s"""/.bsp/scala-cli.json
       |${ScalaCli.scalaCliBspJsonContent()}
       |
       |/.scala-build/ide-inputs.json
       |${BaseScalaCliSuite.scalaCliIdeInputJson(".")}
       |
       |""".stripMargin

  protected def scalaCliInitialize(
      useBsp: Boolean
  )(layout: String): Future[InitializeResult] = {
    if (!useBsp)
      importedPromise = Promise[Unit]()
    initialize(
      (if (useBsp) bspLayout else manualLayout) + layout
    )
  }

  protected def waitForImport(useBsp: Boolean): Future[Unit] =
    if (useBsp) Future.successful(())
    else
      Future
        .firstCompletedOf(
          List(
            importedPromise.future,
            timeout("import timeout", 180.seconds),
          )
        )

}

object BaseScalaCliSuite {

  def scalaCliIdeInputJson(args: String*): String = {
    val ideInputJson = ujson.Obj(
      "args" -> args
    )
    ujson.write(ideInputJson)
  }
}

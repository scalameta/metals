package tests.sbtserver

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.meta.metals.sbtserver.Sbt
import scala.meta.metals.sbtserver.SbtServer
import scala.util.Failure
import scala.util.Success
import monix.execution.Scheduler.Implicits.global
import org.langmeta.internal.io.PathIO
import scala.meta.jsonrpc.Services
import scala.meta.lsp.TextDocument
import scala.meta.lsp.Window
import tests.MegaSuite

case class SbtServerConnectionError(msg: String) extends Exception(msg)

object SbtServerTest extends MegaSuite {

  test("correct sbt 1.1 project establishes successful connection") {
    val services = Services.empty(logger)
      .notification(Window.logMessage)(msg => ())
      .notification(TextDocument.publishDiagnostics)(msg => ())
    val program = for {
      sbt <- SbtServer.connect(PathIO.workingDirectory, services).map {
        case Left(err) => throw SbtServerConnectionError(err)
        case Right(ok) =>
          println("Established connection to sbt server.")
          ok
      }
      response <- Sbt.setting.query("metals/crossScalaVersions")(sbt.client)
    } yield {
      val Right(json) = response
      val Right(crossScalaVersions) = json.value.as[List[String]]
      sbt.runningServer.cancel()
      assertEquals(crossScalaVersions, List("2.12.4"))
      crossScalaVersions
    }
    val result = Await.result(program.materialize.runAsync, Duration(5, "s"))
    result match {
      case Success(_) => // hurrah :clap:
      case Failure(err) => throw err
    }
  }

}

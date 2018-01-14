package tests.sbtserver

import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.meta.languageserver.sbtserver.Sbt
import scala.meta.languageserver.sbtserver.SbtServer
import scala.util.Failure
import scala.util.Success
import monix.execution.Scheduler.Implicits.global
import org.langmeta.internal.io.PathIO
import org.langmeta.jsonrpc.Services
import org.langmeta.lsp.TextDocument
import org.langmeta.lsp.Window
import tests.MegaSuite

case class SbtServerConnectionError(msg: String) extends Exception(msg)

object SbtServerTest extends MegaSuite {

  test("correct sbt 1.1 project establishes successful connection") {
    val services = Services.empty
      .notification(Window.logMessage)(msg => ())
      .notification(TextDocument.publishDiagnostics)(msg => ())
    val program = for {
      sbt <- SbtServer.connect(PathIO.workingDirectory, services).map {
        case Left(err) => throw SbtServerConnectionError(err)
        case Right(ok) =>
          println("Established connection to sbt server.")
          ok
      }
      response <- Sbt.setting.query("metaserver/crossScalaVersions")(sbt.client)
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

package tests.sbtserver

import java.nio.charset.StandardCharsets
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.meta.languageserver.sbtserver.Sbt
import scala.meta.languageserver.sbtserver.SbtServer
import scala.util.Failure
import scala.util.Success
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.reactive.Observable
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
import org.langmeta.jsonrpc.Services
import tests.MegaSuite

case class SbtServerConnectionError(msg: String) extends Exception(msg)

object SbtServerTest extends MegaSuite {
  def openConnection(cwd: AbsolutePath): Task[SbtServer] = {
    for {
      process <- Task.defer {
        val p = new ProcessBuilder("sbt")
          .directory(cwd.toFile)
          .start()
        Observable
          .fromInputStream(p.getInputStream)
          .map[String](bytes => new String(bytes, StandardCharsets.UTF_8))
          .doOnNext(print)
          .findL(_.contains("sbt server"))
          .map(_ => p)
      }
      sbt <- SbtServer.connect(cwd, Services.empty).map { response =>
        println("Stopping sbt process..")
        // NOTE(olafur) sbt still seems to keep on running despite this
        process.getOutputStream.close()
        process.getInputStream.close()
        process.destroyForcibly()
        response match {
          case Left(err) => throw SbtServerConnectionError(err)
          case Right(ok) =>
            println("Established connection to sbt server.")
            ok
        }
      }
    } yield sbt
  }

  test("correct sbt 1.1 project establishes successful connection") {
    val sbt1project =
      PathIO.workingDirectory.resolve("..").resolve("test-workspace-sbt-1.1")
    openConnection(sbt1project)
    val program = for {
      sbt <- openConnection(sbt1project)
      response <- Sbt.setting.query("a/crossScalaVersions")(sbt.client)
    } yield {
      val Right(json) = response
      val Right(crossScalaVersions) = json.value.as[List[String]]
      assertEquals(crossScalaVersions, List("2.12.4"))
      crossScalaVersions
    }
    val result = Await.result(program.materialize.runAsync, Duration(30, "s"))
    result match {
      case Success(_) => // hurrah :clap:
      case Failure(err) => throw err
    }
  }

}

package tests.feature

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.{BuildInfo => V}
import scala.meta.internal.semver.SemVer

import coursierapi.Complete

class Worksheet211LspSuite extends tests.BaseWorksheetLspSuite(V.scala211)

class LatestWorksheet3LspSuite
    extends tests.BaseWorksheetLspSuite(
      V.supportedScala3Versions
        .sortWith(SemVer.isCompatibleVersion)
        .reverse
        .head
    ) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

class Worksheet3NextSuite
    extends tests.BaseWorksheetLspSuite(Worksheet3NextSuite.scala3Next) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

object Worksheet3NextSuite {
  def scala3Next: String =
    Complete
      .create()
      .withInput("org.scala-lang:scala3-compiler_3:")
      .complete()
      .getCompletions()
      .asScala
      .toList
      .reverse
      .collectFirst { version =>
        SemVer.Version.fromString(version) match {
          case SemVer.Version(_, _, _, None, None, None) => version
        }
      }
      .get
}

class Worksheet212LspSuite extends tests.BaseWorksheetLspSuite(V.scala212)

class Worksheet213LspSuite extends tests.BaseWorksheetLspSuite(V.scala213) {

  test("akka") {
    cleanWorkspace()
    val path = "hi.worksheet.sc"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {}
           |}
           |/${path}
           |import $$dep.`com.typesafe.akka::akka-stream:2.6.13`
           |
           |import akka.actor.ActorSystem
           |import akka.NotUsed
           |import akka.stream.scaladsl.Source
           |import akka.stream.scaladsl.Sink
           |import java.io.File
           |import scala.concurrent.Await
           |import scala.concurrent.duration.DurationInt
           |
           |
           |implicit val system: ActorSystem = ActorSystem("QuickStart")
           |val source: Source[Int, NotUsed] = Source(1 to 2)
           |val future = source.runWith(Sink.foreach(_ => ()))
           |Await.result(future, 3.seconds)
           |
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ = assertNoDiff(
        // it seems that part of the string is always different, so let's remove it
        client.workspaceDecorations(path).replaceAll(".out\\(.*", ".out(..."),
        """|import $dep.`com.typesafe.akka::akka-stream:2.6.13`
           |
           |import akka.actor.ActorSystem
           |import akka.NotUsed
           |import akka.stream.scaladsl.Source
           |import akka.stream.scaladsl.Sink
           |import java.io.File
           |import scala.concurrent.Await
           |import scala.concurrent.duration.DurationInt
           |
           |
           |implicit val system: ActorSystem = ActorSystem("QuickStart") // : ActorSystem = akka://QuickStart
           |val source: Source[Int, NotUsed] = Source(1 to 2) // : Source[Int, NotUsed] = Source(SourceShape(StatefulMapConcat.out(...
           |val future = source.runWith(Sink.foreach(_ => ())) // : concurrent.Future[akka.Done] = Future(Success(Done))
           |Await.result(future, 3.seconds) // : akka.Done = Done
           |""".stripMargin,
      )
    } yield ()
  }
}

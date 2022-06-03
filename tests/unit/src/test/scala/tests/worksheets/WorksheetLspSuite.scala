package tests.worksheets

import scala.meta.internal.metals.{BuildInfo => V}

import munit.TestOptions

class WorksheetLspSuite extends tests.BaseWorksheetLspSuite(V.scala3) {

  override def versionSpecificCodeToValidate: String =
    """given str: String = """""

  override def versionSpecificScalacOptionsToValidate: List[String] = List(
    "-Ycheck-reentrant"
  )
  checkWorksheetDeps(
    "imports-inside",
    "a/src/main/scala/foo/Main.worksheet.sc",
  )

  checkWorksheetDeps("imports-outside", "Main.worksheet.sc")

  def checkWorksheetDeps(opts: TestOptions, path: String): Unit = {
    test(opts) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{
             |  "a": {}
             |}
             |/$path
             |import $$dep.`com.lihaoyi::scalatags:0.12.0`
             |import scalatags.Text.all._
             |val htmlFile = html(
             |  body(
             |    p("This is a big paragraph of text")
             |  )
             |)
             |htmlFile.render
             |""".stripMargin
        )
        _ <- server.didOpen(path)
        _ <- server.didSave(path)(identity)
        identity <- server.completion(
          path,
          "htmlFile.render@@",
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          s"""|/$path
              |import $$dep/*<no symbol>*/.`com.lihaoyi::scalatags:0.12.0`/*<no symbol>*/
              |import scalatags.Text/*Text.scala*/.all/*Text.scala*/._
              |val htmlFile/*L2*/ = html/*Tags.scala*/(
              |  body/*Tags.scala*/(
              |    p/*Tags.scala*/("This is a big paragraph of text")
              |  )
              |)
              |htmlFile/*L2*/.render/*Text.scala*/
              |""".stripMargin,
        )
        _ <- server.didOpen("scalatags/Text.scala")
        _ = assertNoDiff(identity, "render: String")
        _ = assertNoDiagnostics()
        _ = assertNoDiff(
          client.workspaceDecorations(path),
          """|import $dep.`com.lihaoyi::scalatags:0.12.0`
             |import scalatags.Text.all._
             |val htmlFile = html(
             |  body(
             |    p("This is a big paragraph of text")
             |  )
             |) // : scalatags.Text.TypedTag[String] = TypedTag( tag = "html", modifiers = List( ArraySeq( TypedTag( tag = "body", modifie…
             |htmlFile.render // : String = "<html><body><p>This is a big paragraph of text</p></body></html>"
             |""".stripMargin,
        )
      } yield ()
    }
  }

  test("bad-dep") {
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
           |import $$dep.`com.lihaoyi::scalatags:0.999.0`
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ = assertNoDiff(
        client.workspaceErrorShowMessages,
        "Error downloading com.lihaoyi:scalatags_3:0.999.0",
      )
    } yield ()
  }
  // Ensure that on Java +9 that all modules are correctly loaded with the Mdoc
  // classloader including things like the java.sql module.
  // https://github.com/scalameta/metals/issues/2187
  test("classloader") {
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
           |new java.sql.Date(100L)
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ = assertNoDiff(
        client.syntheticDecorations,
        "new java.sql.Date(100L) // : Date = 1970-01-01",
      )
    } yield ()
  }

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
        client.syntheticDecorations.replaceAll(".out\\(.*", ".out(..."),
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
           |val future = source.runWith(Sink.foreach(_ => ())) // : concurrent.Future[akka.Done] = Future(<not completed>)
           |Await.result(future, 3.seconds) // : akka.Done = Done
           |""".stripMargin,
      )
    } yield ()
  }

  test("literals") {
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {"scalaVersion": "${V.scala213}"}}
           |/a/src/main/scala/foo/Main.worksheet.sc
           |val literal: 42 = 42
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/foo/Main.worksheet.sc")
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")(identity)
      _ = assertNoDiagnostics()
    } yield ()
  }
}

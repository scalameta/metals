package tests.worksheets

import scala.meta.internal.metals.{BuildInfo => V}

import munit.TestOptions

class WorksheetLspSuite extends tests.BaseWorksheetLspSuite(V.scala213) {

  val oldImportSyntaxF: String => String = { (name: String) =>
    s"import $$ivy.`$name`"
  }
  val usingSyntaxF: String => String = { (name: String) =>
    s"//> using dep $name"
  }

  checkWorksheetDeps(
    "imports-inside",
    "a/src/main/scala/foo/Main.worksheet.sc",
  )

  checkWorksheetDeps("imports-outside", "Main.worksheet.sc")

  def checkWorksheetDeps(opts: TestOptions, path: String): Unit = {
    for {
      (suffix, depFunc) <- List(
        "ivy" -> oldImportSyntaxF,
        "dep" -> usingSyntaxF,
      )
    } yield test(opts.withName(s"${opts.name}-$suffix")) {
      cleanWorkspace()
      for {
        _ <- initialize(
          s"""
             |/metals.json
             |{
             |  "a": {}
             |}
             |/$path
             |${depFunc("com.lihaoyi::scalatags:0.9.0")}
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
        _ <- server.didSave(path)
        identity <- server.completion(
          path,
          "htmlFile.render@@",
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          s"""|/$path
              |${depFunc("com.lihaoyi::scalatags:0.9.0").replace("$ivy", "$ivy/*<no symbol>*/").replace("0`", "0`/*<no symbol>*/")}
              |import scalatags.Text/*Text.scala*/.all/*Text.scala*/._
              |val htmlFile/*L2*/ = html/*;Tags.scala;Text.scala*/(
              |  body/*;Tags.scala;Text.scala*/(
              |    p/*;Tags.scala;Text.scala*/("This is a big paragraph of text")
              |  )
              |)
              |htmlFile/*L2*/.render/*Text.scala*/
              |""".stripMargin,
        )
        _ <- server.didOpen("scalatags/Text.scala")
        _ = assertNoDiff(identity, "render: String")
        _ = assertNoDiagnostics()
        _ <- server.assertInlayHints(
          path,
          s"""|${depFunc("com.lihaoyi::scalatags:0.9.0")}
              |import scalatags.Text.all._
              |val htmlFile = html(
              |  body(
              |    p("This is a big paragraph of text")
              |  )
              |)/* // : scalatags.Text.TypedTag[String] = TypedTag( tag = "html", modifiers = List( ArraySeq( TypedTag( tag = "body", modifie…*/
              |htmlFile.render/* // : String = "<html><body><p>This is a big paragraph of text</p></body></html>"*/
              |""".stripMargin,
        )
      } yield ()
    }
  }

  for {
    (suffix, depFunc) <- List(
      "ivy" -> oldImportSyntaxF,
      "dep" -> usingSyntaxF,
    )
  } yield test(s"bad-dep-$suffix") {
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
           |${depFunc("com.lihaoyi::scalatags:0.999.0")}
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ = assertNoDiff(
        client.workspaceErrorShowMessages,
        "Error downloading com.lihaoyi:scalatags_2.13:0.999.0",
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
           |new java.sql.RowId{
           |  def getBytes(): Array[Byte] = ???
           |  override def toString(): String = "Row!"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.assertInlayHints(
        path,
        """|new java.sql.RowId{
           |  def getBytes(): Array[Byte] = ???
           |  override def toString(): String = "Row!"
           |}/* // : Object with java.sql.RowId = Row!*/
           |""".stripMargin,
      )
    } yield ()
  }

  for {
    (suffix, depFunc) <- List(
      "ivy" -> oldImportSyntaxF,
      "dep" -> usingSyntaxF,
    )
  } yield test(s"akka-$suffix") {
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
           |${depFunc("com.typesafe.akka::akka-stream:2.6.13")}
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
           |Await.result(source.runWith(Sink.foreach(_ => ())), 3.seconds)
           |
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      _ <- server.assertInlayHints(
        path,
        s"""|${depFunc("com.typesafe.akka::akka-stream:2.6.13")}
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
            |implicit val system: ActorSystem = ActorSystem("QuickStart")/* // : ActorSystem = akka://QuickStart*/
            |val source: Source[Int, NotUsed] = Source(1 to 2)/* // : Source[Int, NotUsed] = Source(SourceShape(StatefulMapConcat.out(...*/
            |Await.result(source.runWith(Sink.foreach(_ => ())), 3.seconds)/* // : akka.Done = Done*/
            |""".stripMargin,
        postprocessObtained = _.replaceAll(".out\\(.*", ".out(...*/"),
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
      _ <- server.didSave("a/src/main/scala/foo/Main.worksheet.sc")
      _ = assertNoDiagnostics()
    } yield ()
  }
}

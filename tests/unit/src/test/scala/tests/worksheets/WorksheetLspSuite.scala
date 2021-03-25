package tests.worksheets

import scala.meta.internal.metals.{BuildInfo => V}

import munit.TestOptions

class WorksheetLspSuite extends tests.BaseWorksheetLspSuite(V.scala212) {

  checkWorksheetDeps(
    "imports-inside",
    "a/src/main/scala/foo/Main.worksheet.sc"
  )

  checkWorksheetDeps("imports-outside", "Main.worksheet.sc")

  def checkWorksheetDeps(opts: TestOptions, path: String): Unit = {
    test(opts) {
      for {
        _ <- server.initialize(
          s"""
             |/metals.json
             |{
             |  "a": {}
             |}
             |/$path
             |import $$dep.`com.lihaoyi::scalatags:0.9.0`
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
          "htmlFile.render@@"
        )
        _ = assertNoDiff(
          server.workspaceDefinitions,
          s"""|/$path
              |import $$dep/*<no symbol>*/.`com.lihaoyi::scalatags:0.9.0`/*<no symbol>*/
              |import scalatags.Text/*Text.scala*/.all/*Text.scala*/._
              |val htmlFile/*L2*/ = html/*Text.scala*/(
              |  body/*Text.scala*/(
              |    p/*Text.scala*/("This is a big paragraph of text")
              |  )
              |)
              |htmlFile/*L2*/.render/*Text.scala*/
              |""".stripMargin
        )
        _ <- server.didOpen("scalatags/Text.scala")
        _ = assertNoDiff(identity, "render: String")
        _ = assertNoDiagnostics()
        _ = assertNoDiff(
          client.workspaceDecorations,
          """|import $dep.`com.lihaoyi::scalatags:0.9.0`
             |import scalatags.Text.all._
             |val htmlFile = html(
             |  body(
             |    p("This is a big paragraph of text")
             |  )
             |) // : scalatags.Text.TypedTag[String] = TypedTag("html",List(WrappedArray(TypedTag("body",List(WrappedArray(TypedTag("p",Li…
             |htmlFile.render // : String = "<html><body><p>This is a big paragraph of text</p></body></html>"
             |""".stripMargin
        )
      } yield ()
    }
  }

  test("bad-dep") {
    val path = "hi.worksheet.sc"
    for {
      _ <- server.initialize(
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
        "Error downloading com.lihaoyi:scalatags_2.12:0.999.0"
      )
    } yield ()
  }
  // Ensure that on Java +9 that all modules are correctly loaded with the Mdoc
  // classloader including things like the java.sql module.
  // https://github.com/scalameta/metals/issues/2187
  test("classloader") {
    val path = "hi.worksheet.sc"
    for {
      _ <- server.initialize(
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
        client.workspaceDecorations,
        "new java.sql.Date(100L) // : java.sql.Date = 1970-01-01"
      )
    } yield ()
  }
}

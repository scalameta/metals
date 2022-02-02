package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

class Worksheet211LspSuite extends tests.BaseWorksheetLspSuite(V.scala211)

class Worksheet3LspSuite extends tests.BaseWorksheetLspSuite(V.scala3) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

class Worksheet213LspSuite extends tests.BaseWorksheetLspSuite(V.scala213) {

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

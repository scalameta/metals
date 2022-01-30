package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

abstract class Worksheet211LspSuite(
    useVirtualDocuments: Boolean,
    suiteNameSuffix: String
) extends tests.BaseWorksheetLspSuite(
      V.scala211,
      useVirtualDocuments,
      suiteNameSuffix
    )
class Worksheet211LspSaveToDiskSuite
    extends Worksheet211LspSuite(false, "save-to-disk")

class Worksheet211LspVirtualDocSuite
    extends Worksheet211LspSuite(true, "virtual-docs")

abstract class Worksheet3LspSuite(
    useVirtualDocuments: Boolean,
    suiteNameSuffix: String
) extends tests.BaseWorksheetLspSuite(
      V.scala3,
      useVirtualDocuments,
      suiteNameSuffix
    ) {
  override def versionSpecificCodeToValidate: String =
    """given str: String = """""
}

class Worksheet3LspSaveToDiskSuite
    extends Worksheet3LspSuite(false, "save-to-disk")

class Worksheet3LspVirtualDocSuite
    extends Worksheet3LspSuite(true, "virtual-docs")
abstract class Worksheet213LspSuite(
    useVirtualDocuments: Boolean,
    suiteNameSuffix: String
) extends tests.BaseWorksheetLspSuite(
      V.scala213,
      useVirtualDocuments,
      suiteNameSuffix
    ) {

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

class Worksheet213LspSaveToDiskSuite
    extends Worksheet213LspSuite(false, "save-to-disk")

class Worksheet213LspVirtualDocSuite
    extends Worksheet213LspSuite(true, "virtual-docs")

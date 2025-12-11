package tests

import scala.meta.internal.metals.codeactions.ExplainDiagnostic

import tests.BaseLspSuite

class Scala3LspSuite extends BaseLspSuite("scala3") {

  test("import-capture") {
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.7.2"
           |  }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |import language.experimental.captureChecking
           |import java.io.FileOutputStream
           |
           |def usingLogFile[T](op: FileOutputStream^ => T): T =
           |  ???
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didSave("a/src/main/scala/a/A.scala")
    } yield {
      assertNoDiagnostics()
    }
  }

  test("import-capture-second") {
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.7.2"
           |  }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |import java.io.FileOutputStream
           |import scala.language.experimental.captureChecking
           |
           |def usingLogFile[T](op: FileOutputStream^ => T): T =
           |  ???
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didSave("a/src/main/scala/a/A.scala")
    } yield {
      assertNoDiagnostics()
    }
  }

  test("import-capture-wrong") {
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.7.2"
           |  }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |import java.io.FileOutputStream
           |
           |object O:
           |  import scala.language.experimental.captureChecking
           |
           |  def usingLogFile[T](op: FileOutputStream^ => T): T =
           |    ???
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didSave("a/src/main/scala/a/A.scala")
    } yield {
      assertNoDiff(
        client.workspaceDiagnostics,
        """
          |a/src/main/scala/a/A.scala:6:38: error: this language import is only allowed at the toplevel
          |  import scala.language.experimental.captureChecking
          |                                     ^^^^^^^^^^^^^^^
          |a/src/main/scala/a/A.scala:8:45: error: `identifier` expected but `=>` found
          |  def usingLogFile[T](op: FileOutputStream^ => T): T =
          |                                            ^^
          |""".stripMargin,
      )
    }
  }

  test("explain-code-description-virtual".tag(TestingServer.virtualDocTag)) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.8.1-RC1-bin-20260105-f08de70-NIGHTLY",
           |    "repositories": [
           |      "https://repo.scala-lang.org/artifactory/maven-nightlies"
           |    ]
           |  }
           |}
           |/a/src/main/scala/a/MyValueTrait.scala
           |package a
           |trait MyValueTrait extends AnyVal
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/MyValueTrait.scala")
      _ <- server.didSave("a/src/main/scala/a/MyValueTrait.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |a/src/main/scala/a/MyValueTrait.scala:2:7: error: trait MyValueTrait cannot extend AnyVal
          |trait MyValueTrait extends AnyVal
          |      ^
          |""".stripMargin,
      )
      diag = client.diagnostics.head._2.headOption
        .getOrElse(throw new RuntimeException("No diagnostic found"))
      code = diag.getCodeDescription().getHref()
      result <- server.executeDecodeFileCommand(code)
      _ = assertNoDiff(
        result.value,
        """|# Explained Diagnostic
           |
           |**Source File**: a/src/main/scala/a/MyValueTrait.scala
           |**Position**: Line 2, Column 7
           |
           |## Detailed Explanation
           |
           |```
           |[presentation compiler] error at line 2
           |```
           |
           |trait MyValueTrait cannot extend AnyVal
           |
           |# Explanation (enabled by `-explain`)
           |
           |Only classes (not traits) are allowed to extend AnyVal, but traits may extend
           |Any to become "universal traits" which may only have def members.
           |Universal traits can be mixed into classes that extend AnyVal.""".stripMargin,
      )
      _ <- server.assertCodeAction(
        "a/src/main/scala/a/MyValueTrait.scala",
        s"""|package a
            |trait <<MyValueTrait>> extends AnyVal
            |""".stripMargin,
        "",
        Nil,
      )
    } yield {}
  }

  test("explain-code-description") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.8.1-RC1-bin-20260105-f08de70-NIGHTLY",
           |    "repositories": [
           |      "https://repo.scala-lang.org/artifactory/maven-nightlies"
           |    ]
           |  }
           |}
           |/a/src/main/scala/a/MyValueTrait.scala
           |package a
           |trait MyValueTrait extends AnyVal
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/MyValueTrait.scala")
      _ <- server.didSave("a/src/main/scala/a/MyValueTrait.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |a/src/main/scala/a/MyValueTrait.scala:2:7: error: trait MyValueTrait cannot extend AnyVal
          |trait MyValueTrait extends AnyVal
          |      ^
          |""".stripMargin,
      )
      diag = client.diagnostics.head._2.headOption
        .getOrElse(throw new RuntimeException("No diagnostic found"))
      _ = assert(
        diag.getCodeDescription() == null,
        "Code description should be null",
      )
      _ <- server.assertCodeAction(
        "a/src/main/scala/a/MyValueTrait.scala",
        s"""|package a
            |trait <<MyValueTrait>> extends AnyVal
            |""".stripMargin,
        s"""|${ExplainDiagnostic.title}""".stripMargin,
        Nil,
      )
    } yield {}
  }

  test(
    "explain-code-description-old-scala-version",
    withoutVirtualDocs = true,
  ) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.7.4",
           |    "repositories": [
           |      "https://repo.scala-lang.org/artifactory/maven-nightlies"
           |    ]
           |  }
           |}
           |/a/src/main/scala/a/MyValueTrait.scala
           |package a
           |trait MyValueTrait extends AnyVal
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/MyValueTrait.scala")
      _ <- server.didSave("a/src/main/scala/a/MyValueTrait.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """
          |a/src/main/scala/a/MyValueTrait.scala:2:7: error: trait MyValueTrait cannot extend AnyVal
          |trait MyValueTrait extends AnyVal
          |      ^
          |""".stripMargin,
      )
      diag = client.diagnostics.head._2.headOption
        .getOrElse(throw new RuntimeException("No diagnostic found"))
      _ = assert(
        diag.getCodeDescription() == null,
        "Code description should be null",
      )
      _ <- server.assertCodeAction(
        "a/src/main/scala/a/MyValueTrait.scala",
        s"""|package a
            |trait <<MyValueTrait>> extends AnyVal
            |""".stripMargin,
        "",
        Nil,
      )
    } yield {}
  }
}

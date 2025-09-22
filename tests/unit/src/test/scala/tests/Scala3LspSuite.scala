package tests

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
}

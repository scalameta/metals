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
}

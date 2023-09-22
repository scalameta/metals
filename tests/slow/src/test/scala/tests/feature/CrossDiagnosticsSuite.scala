package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseLspSuite

class CrossDiagnosticsSuite extends BaseLspSuite("slow-diagnostics") {

  test("kind-projector") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": { "scalaVersion": "${V.scala3}" }
           |}
           |/a/src/main/scala/a/A.scala
           |
           |object *
           |
           |given Conversion[*.type, List[*.type]] with
           |  def apply(ast: *.type) = ast :: Nil
           |
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didChange("a/src/main/scala/a/A.scala")(txt => txt + "\n")
      _ = assertNoDiagnostics()
    } yield ()
  }
}

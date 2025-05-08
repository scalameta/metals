package tests

import scala.concurrent.Future

class CompilersLspSuite extends BaseCompletionLspSuite("compilers") {
  test("reset-pc".flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {},
          |  "b": { "dependsOn": ["a"] }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ <- Future.sequence(
        List('a', 'b').map { project =>
          assertCompletion(
            "completeThisUniqueNa@@",
            "completeThisUniqueName(): Int",
            project = project,
          )
        }
      )
      count = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(
        2,
        count,
      )
      _ <-
        server.didChange("b/src/main/scala/b/B.scala")(_ =>
          "package b; object B"
        )
      _ <-
        server.didSave("b/src/main/scala/b/B.scala")
      _ <-
        server.didChange("a/src/main/scala/a/A.scala")(_ =>
          "package a; class A"
        )
      _ <-
        server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      countAfter = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(
        0,
        countAfter,
      )
    } yield ()
  }
}

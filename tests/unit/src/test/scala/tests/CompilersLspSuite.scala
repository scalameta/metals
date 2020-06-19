package tests

import scala.concurrent.Future

class CompilersLspSuite extends BaseCompletionLspSuite("compilers") {
  test("reset-pc") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """/metals.json
          |{
          |  "a": {},
          |  "b": { "dependsOn": ["a"] }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
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
        List('a', 'b').map(project =>
          assertCompletion(
            "completeThisUniqueNa@@",
            "completeThisUniqueName(): Int",
            project = project
          )
        )
      )
      _ = assertEquals(2, server.server.loadedPresentationCompilerCount())
      _ <-
        server.didSave("b/src/main/scala/b/B.scala")(_ => "package b; object B")
      _ <-
        server.didSave("a/src/main/scala/a/A.scala")(_ => "package a; object A")
      _ = assertNoDiagnostics()
      _ = assertEquals(0, server.server.loadedPresentationCompilerCount())
    } yield ()
  }
}

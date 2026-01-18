package tests

import scala.concurrent.Future

class CompilersLspSuite extends BaseCompletionLspSuite("compilers") {
  test("reset-pc") {
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

  test("cache-eviction") {
    cleanWorkspace()
    val numTargets = 35
    val targets = (1 to numTargets).map(i => s"p$i").toList
    val metalsJson =
      targets.map(t => s""""$t": {}""").mkString("{\n", ",\n", "\n}")
    val fileStruct = targets
      .map { t =>
        s"""|/$t/src/main/scala/$t/Main.scala
            |package $t
            |object Main {
            |  val x = 42
            |}""".stripMargin
      }
      .mkString("\n")

    val layout = s"""|/metals.json
                     |$metalsJson
                     |$fileStruct
                     |""".stripMargin

    for {
      _ <- initialize(layout)
      _ <- Future.sequence(targets.map { t =>
        server.didOpen(s"$t/src/main/scala/$t/Main.scala")
      })
      _ <- server.server.compilers.load(
        targets.map(t => server.toPath(s"$t/src/main/scala/$t/Main.scala"))
      )

      count = server.server.loadedPresentationCompilerCount()
      _ = {
        assert(count <= 32, s"Expected count <= 32, got $count")
      }
    } yield ()
  }
}

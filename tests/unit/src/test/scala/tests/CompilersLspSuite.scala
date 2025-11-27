package tests

import scala.concurrent.Future

import scala.meta.internal.metals.MetalsServerConfig

class CompilersLspSuite
    extends BaseCompletionLspSuite("compilers")
    with BaseSourcePathSuite {

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(loglevel = "debug")

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

  test("fallback-compiler") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  def callMeMaybe() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a.A
          |object B {
          |  def foobar() = new A().callMeMaybe("foobar")
          |}
        """.stripMargin
      )
      // trigger the fallback compiler since B is not part of a build target
      // and test that it correctly understands the source in A and issues an error
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:4:38: error: no arguments allowed for nullary method callMeMaybe: (): Int
           |  def foobar() = new A().callMeMaybe("foobar")
           |                                     ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("fallback-compiler-exclusion") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  def callMeMaybe() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a.A
          |object B {
          |  def foobar() = new A().callMeMaybe()
          |}
          |/b/src/main/scala/b/C.scala
          |package b
          |import a.A
          |import experimental.Experimental
          |object C {
          |  def foobar() = new A().callMeMaybe()
          |  def foobar() = new Experimental().callMeMaybe()
          |}
          |/experimental/src/main/scala/experimental/Experimental.scala
          |package experimental
          |class Experimental {
          |  def callMeMaybe() = 42
          |}
        """.stripMargin
      )
      // trigger the fallback compiler since B is not part of a build target
      // and test that it correctly understands the source in A and issues an error
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ <- server.didOpen("b/src/main/scala/b/C.scala")
      _ <- server.didFocus("b/src/main/scala/b/C.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/C.scala:3:8: error: not found: object experimental
           |import experimental.Experimental
           |       ^^^^^^^^^^^^
           |b/src/main/scala/b/C.scala:6:22: error: not found: type Experimental
           |  def foobar() = new Experimental().callMeMaybe()
           |                     ^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}

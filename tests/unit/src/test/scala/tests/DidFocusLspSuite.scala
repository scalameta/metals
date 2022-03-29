package tests

import scala.meta.internal.metals.DidFocusResult._
import scala.meta.internal.metals.Time

class DidFocusLspSuite extends BaseLspSuite("did-focus") {
  var fakeTime: FakeTime = _
  override def time: Time = fakeTime
  override def beforeEach(context: BeforeEach): Unit = {
    fakeTime = new FakeTime()
    super.beforeEach(context)
  }
  test("is-compiled") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {
          |    "dependsOn": ["a"]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val x = 1
          |}
          |/a/src/main/scala/a/A2.scala
          |package a
          |object A2 {
          |  val y = 1
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object C {
          |  val z: Int = a.A.x
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ = fakeTime.elapseSeconds(10)
      didCompile <- server.didFocus("a/src/main/scala/a/A2.scala")
      _ = assert(didCompile == AlreadyCompiled)
      didCompile <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assert(didCompile == AlreadyCompiled)
      _ <- server.didSave("a/src/main/scala/a/A.scala")(
        _.replace("val x = 1", "val x = \"string\"")
      )
      _ = fakeTime.elapseSeconds(10)
      _ = assertNoDiagnostics()
      didCompile <- server.didFocus("a/src/main/scala/a/A2.scala")
      _ = assert(didCompile == AlreadyCompiled)
      didCompile <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assert(didCompile == Compiled)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:16: error: type mismatch;
           | found   : String
           | required: Int
           |  val z: Int = a.A.x
           |               ^^^^^
           |""".stripMargin
      )
    } yield ()
  }

  // Ignore flaky test, see the details: https://github.com/scalameta/metals/pull/3752#issuecomment-1079878023
  test("497".ignore) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {},
          |  "b": {
          |    "dependsOn": ["a"]
          |  }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val x: Int = 1
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  val y: Int = 2
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      xMismatch = {
        """|a/src/main/scala/a/A.scala:3:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val x: Int = ""
           |               ^^
           |""".stripMargin
      }
      _ = fakeTime.elapseSeconds(10)
      _ <- server.didSave("a/src/main/scala/a/A.scala")(
        _.replace("1", "\"\"")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        xMismatch
      )
      _ <- server.didSave("b/src/main/scala/b/B.scala")(
        _.replace("2", "\"\"")
      )
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        xMismatch
      )
      didSaveA = server.didSave("a/src/main/scala/a/A.scala")(
        _.replace("Int", "String")
      )
      // Focus before compilation of A.scala is complete.
      didCompile <- server.didFocus("b/src/main/scala/b/B.scala")
      _ <- didSaveA
      _ = assert(didCompile == Compiled)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val y: Int = ""
           |               ^^
           |""".stripMargin
      )
    } yield ()
  }
}

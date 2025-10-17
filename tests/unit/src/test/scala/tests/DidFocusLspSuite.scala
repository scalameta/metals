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
          |  },
          |  "c": {}
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
          |/c/src/main/scala/c/C.scala
          |package c
          |object E {
          | val i: Int = "aaa"
          |}
        """.stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ = fakeTime.elapseSeconds(10)
      didCompile <- server.didFocus("a/src/main/scala/a/A2.scala")
      _ = assert(didCompile == Compiled, s"didCompile = $didCompile")
      didCompile <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assert(didCompile == Compiled, s"didCompile = $didCompile")
      _ <- server.didFocus("c/src/main/scala/c/C.scala")
      // fake delete the diagnostic to see that `c` won't get recompiled
      _ = client.diagnostics(server.toPath("c/src/main/scala/c/C.scala")) =
        Seq.empty
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("val x = 1", "val x = \"string\"")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = fakeTime.elapseSeconds(10)
      _ = assertNoDiagnostics()
      didCompile <- server.didFocus("a/src/main/scala/a/A2.scala")
      // DATABRICKS: it could be that the order in which projects are compiled is slightly different
      // due to changes on cascade compile and the likes, this was Compiled in OSS
      _ = assert(didCompile == AlreadyCompiled, s"didCompile = $didCompile")
      didCompile <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assert(didCompile == Compiled, s"didCompile = $didCompile")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:16: error: type mismatch;
           | found   : String
           | required: Int
           |  val z: Int = a.A.x
           |               ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("compiled-focused") {
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
      _ <- server.didFocus("b/src/main/scala/b/B.scala")
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("val x = 1", "val x = \"string\"")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = fakeTime.elapseSeconds(10)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:16: error: type mismatch;
           | found   : String
           | required: Int
           |  val z: Int = a.A.x
           |               ^^^^^
           |""".stripMargin,
      )
    } yield ()
  }
}

// https://github.com/scalameta/metals/issues/497
class DidFocusWhileCompilingLspSuite
    extends BaseLspSuite("did-focus-while-compiling") {
  var fakeTime: FakeTime = _
  val compileDelayMillis = 200
  override def time: Time = fakeTime

  // sleep 5s during the compilation, so that we can make sure
  // calling `didFocus` during compilation.
  override def beforeEach(context: BeforeEach): Unit = {
    fakeTime = new FakeTime()
    onStartCompilation = () => {
      Thread.sleep(compileDelayMillis)
    }
    super.beforeEach(context)
  }

  test(
    "Trigger compilation when current compile may affect the focused buffer"
  ) {
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
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("1", "\"\"")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        xMismatch,
      )
      _ <- server.didChange("b/src/main/scala/b/B.scala")(
        _.replace("2", "\"\"")
      )
      _ <- server.didSave("b/src/main/scala/b/B.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        xMismatch,
      )
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replace("Int", "String")
      )
      _ <- server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:3:16: error: type mismatch;
           | found   : String("")
           | required: Int
           |  val y: Int = ""
           |               ^^
           |""".stripMargin,
      )
    } yield ()
  }
}

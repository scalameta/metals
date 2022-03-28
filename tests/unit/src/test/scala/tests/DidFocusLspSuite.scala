package tests

import scala.concurrent.Future

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
}

// https://github.com/scalameta/metals/issues/497
class DidFocusWhileCompilingLspSuite
    extends BaseLspSuite("did-focus-while-compiling") {
  var fakeTime: FakeTime = _
  val compileDelayMillis = 5000
  override def time: Time = fakeTime

  // sleep 10s during the compilation, so that we can make sure
  // invoking `didFocus` during compilation.
  override def beforeEach(context: BeforeEach): Unit = {
    fakeTime = new FakeTime()
    onStartCompilation = () => {
      Thread.sleep(compileDelayMillis)
    }
    super.beforeEach(context)
  }

  test(
    "Trigger compilation by didFocus when current compile may affect focused buffer"
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
      // Wait until compilation against project a is started (before we invoke didFocus on project b)
      _ <- Future { Thread.sleep(compileDelayMillis / 2) }
      // Focus before compilation of A.scala is complete.
      // And make sure didFocus during the compilation causes compilation against project b.
      didCompile <- server.didFocus("b/src/main/scala/b/B.scala")
      _ <- didSaveA
      _ = assert(
        didCompile == Compiled,
        s"expect 'Compiled', actual: ${didCompile}"
      )
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

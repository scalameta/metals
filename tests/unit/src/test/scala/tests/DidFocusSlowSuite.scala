package tests

import scala.meta.internal.metals.DidFocusResult._
import scala.meta.internal.metals.Time

object DidFocusSlowSuite extends BaseSlowSuite("did-focus") {
  var fakeTime: FakeTime = _
  override def time: Time = fakeTime
  override def utestBeforeEach(path: Seq[String]): Unit = {
    fakeTime = new FakeTime()
    super.utestBeforeEach(path)
  }
  testAsync("is-compiled") {
    for {
      _ <- server.initialize(
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
        _.replaceAllLiterally("val x = 1", "val x = \"string\"")
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

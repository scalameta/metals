package tests

import scala.meta.internal.metals.{BuildInfo => V}

object CompletionSlowSuite extends BaseCompletionSlowSuite("completion") {

  testAsync("basic-212") {
    basicTest(V.scala212)
  }

  testAsync("workspace") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """/metals.json
          |{
          |  "b": {},
          |  "c": {},
          |  "a": { "dependsOn": ["c"] }
          |}
          |/b/src/main/scala/b/DefinedInB.scala
          |package b
          |object DefinedInB {
          |}
          |/c/src/main/scala/c/DefinedInC.scala
          |package c
          |object DefinedInC {
          |}
          |/a/src/main/scala/a/DefinedInA.scala
          |package a
          |object Outer {
          |  class DefinedInA
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object Main {
          |  // DefinedIn
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertEmpty(client.workspaceDiagnostics)
      _ <- server.didChange("a/src/main/scala/a/A.scala")(
        _.replaceAllLiterally("// ", "")
      )
      // assert that "DefinedInB" does not appear in results
      _ <- assertCompletion(
        "DefinedIn@@",
        """|a.Outer.DefinedInA a.Outer
           |c.DefinedInC c
           |""".stripMargin
      )
    } yield ()
  }

  def checkPlugin(name: String, compilerPlugins: String): Unit =
    testAsync(name) {
      for {
        _ <- server.initialize(
          s"""/metals.json
             |{
             |  "a": {
             |    "compilerPlugins": [
             |      $compilerPlugins
             |    ]
             |  }
             |}
             |/a/src/main/scala/a/A.scala
             |package a
             |
             |import scala.concurrent.DelayedLazyVal
             |
             |object Main {
             |  locally {
             |    val x = 2
             |    DelayedLazyVal // here
             |    val y = 1
             |  }
             |}
             |""".stripMargin
        )
        // assert that "DefinedInB" does not appear in results
        _ <- assertCompletion(
          "DelayedLazyVal@@ // here",
          """|DelayedLazyVal scala.concurrent
             |""".stripMargin
        )
      } yield ()
    }

  checkPlugin(
    "empty",
    ""
  )
  checkPlugin(
    "kind-projector",
    """
      |"org.spire-math::kind-projector:0.9.8"
    """.stripMargin
  )
  checkPlugin(
    "better-monadic-for",
    """
      |"com.olegpy::better-monadic-for:0.3.0-M4"
    """.stripMargin
  )

}

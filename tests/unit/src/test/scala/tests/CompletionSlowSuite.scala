package tests

import scala.concurrent.Future
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
          |  // @@
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "DefinedIn@@",
        """|a.Outer.DefinedInA a.Outer
           |c.DefinedInC c
           |""".stripMargin
      )
    } yield ()
  }

  def checkPlugin(
      name: String,
      compilerPlugins: String,
      extra: => Future[Unit] = Future.successful(())
  ): Unit =
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
             |object Main {
             |  // @@
             |}
             |""".stripMargin
        )
        _ <- server.didOpen("a/src/main/scala/a/A.scala")
        _ = assertNoDiagnostics()
        _ <- assertCompletion(
          """
            |import scala.concurrent.DelayedLazyVal
            |
            |locally {
            |  val x = 2
            |  DelayedLazyVal@@
            |  val y = 1
            |}
            |""".stripMargin,
          """|DelayedLazyVal scala.concurrent
             |""".stripMargin
        )
        _ <- extra
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
      |""".stripMargin,
    for {
      _ <- assertCompletion(
        """|def baz[F[_], A]: F[A] = ???
           |baz[Either[Int, ?], String].right@@
           |""".stripMargin,
        "right: Either.RightProjection[Int,String]"
      )
    } yield ()
  )

  checkPlugin(
    "better-monadic-for",
    """|
       |"com.olegpy::better-monadic-for:0.3.0-M4"
       |""".stripMargin
  )

}

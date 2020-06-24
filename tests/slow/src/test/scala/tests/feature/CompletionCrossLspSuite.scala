package tests.feature

import scala.meta.internal.metals.{BuildInfo => V}

import tests.BaseCompletionLspSuite

class CompletionCrossLspSuite
    extends BaseCompletionLspSuite("completion-cross") {

  if (super.isValidScalaVersionForEnv(V.scala211)) {
    test("basic-211") {
      basicTest(V.scala211)
    }
  }

  if (super.isValidScalaVersionForEnv(V.scala213)) {
    test("basic-213") {
      basicTest(V.scala213)
    }
  }

  test("basic-3") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "${V.scala3}" }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |object A {
           |  // @@
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "\"\".substrin@@",
        """|substring(x$0: Int, x$1: Int): String
           |""".stripMargin
      )
      _ <- assertCompletion(
        """
          |locally {
          |  val myLocalVariable = Array("")
          |  myLocalVariable@@
          |  val source = ""
          |}
          |""".stripMargin,
        """|myLocalVariable: Array[String]
           |""".stripMargin
      )
    } yield ()
  }

  test("serializable-2.13") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "${V.scala213}" }
           |}
           |/a/src/main/scala/a/A.scala
           |package a
           |trait Serializable
           |object Main // @@
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "extends Serializable@@",
        """|DefaultSerializable - scala.collection.generic
           |NotSerializableException - java.io
           |Serializable a
           |Serializable - java.io
           |SerializablePermission - java.io
           |""".stripMargin
      )
    } yield ()
  }

  // NOTE(olafur): I'm unable to reproduce failures for this test when running
  // locally but this test still fails when running in CI, see
  // https://github.com/scalameta/metals/pull/1277#issuecomment-578406803
  test("match-213".flaky) {
    matchKeywordTest(V.scala213)
  }
}

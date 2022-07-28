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
    basicTest(V.scala3)
  }

  test("serializable-2.13") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
           |""".stripMargin,
      )
    } yield ()
  }

  // NOTE(olafur): I'm unable to reproduce failures for this test when running
  // locally but this test still fails when running in CI, see
  // https://github.com/scalameta/metals/pull/1277#issuecomment-578406803
  test("match-213".flaky) {
    matchKeywordTest(V.scala213)
  }

  test("extension") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": { "scalaVersion": "${V.scala3}" }
           |}
           |/a/src/main/scala/a/B.scala
           |package b
           |extension (num: Int)
           |  def plus(other: Int) = num + other
           |/a/src/main/scala/a/A.scala
           |package a
           |
           |object A {
           |  // @@
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/B.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletionEdit(
        "1.p@@",
        """|package a
           |
           |import b.plus
           |
           |object A {
           |  1.plus($0)
           |}
           |""".stripMargin,
        filter = _.contains("plus"),
      )
      _ <- assertCompletion(
        "1.pl@@",
        """|plus(other: Int): Int (extension)
           |""".stripMargin,
        filter = _.contains("plus"),
      )
      _ <- assertCompletion(
        "\"plus is not available for string\".plu@@",
        "",
        filter = _.contains("plus"),
      )
    } yield ()
  }
}

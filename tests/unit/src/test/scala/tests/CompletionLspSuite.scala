package tests

import scala.concurrent.Future

import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location

class CompletionLspSuite extends BaseCompletionLspSuite("completion") {

  override def munitIgnore: Boolean = isWindows

  test("basic-212") {
    basicTest(V.scala212)
  }

  test("workspace".flaky) {
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
        """|DefinedInA - a.Outer
           |DefinedInC - c
           |""".stripMargin
      )
    } yield ()
  }

  def checkPlugin(
      name: String,
      compilerPlugins: String,
      extra: => Future[Unit] = Future.successful(())
  )(implicit loc: Location): Unit =
    test(name) {
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

  // FIXME(gabro): the tests don't pass with 2.12.10, although the plugins seem to work fine when
  // tested manually
  if (BuildInfo.scalaVersion != "2.12.10") {
    checkPlugin(
      "kind-projector",
      """
        |"org.spire-math::kind-projector:0.9.8"
        |""".stripMargin,
      for {
        _ <- assertCompletion(
          """|def baz[F[_], A]: F[A] = ???
             |baz[Either[Int, ?], String].fold@@
             |""".stripMargin,
          "fold[C](fa: Int => C, fb: String => C): C"
        )
      } yield ()
    )

    checkPlugin(
      "better-monadic-for",
      """|
         |"com.olegpy::better-monadic-for:0.3.0-M4"
         |""".stripMargin,
      for {
        _ <- assertCompletion(
          """|  for (implicit0(x: String) <- Option(""))
             |    implicitly[String].toCharArr@@
             |""".stripMargin,
          """|toCharArray(): Array[Char]
             |""".stripMargin
        )
      } yield ()
    )
  }

  test("symbol-prefixes") {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |abstract class Base {
          |  def set: scala.collection.mutable.Set[Int]
          |  def list: java.util.List[Int]
          |  def failure: scala.util.Failure[Int]
          |}
          |object Main extends Base {
          |  // @@
          |}
          |""".stripMargin
      )
      _ <- assertCompletion(
        "override def set@@",
        """|def set: mutable.Set[Int]
           |""".stripMargin,
        includeDetail = false
      )
      _ <- assertCompletion(
        "override def list@@",
        """|def list: ju.List[Int]
           |""".stripMargin,
        includeDetail = false
      )
      _ <- assertCompletion(
        "override def failure@@",
        """|def failure: Failure[Int]
           |""".stripMargin,
        includeDetail = false
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "symbol-prefixes": {
          |    "scala/util/": "u"
          |  }
          |}
          |""".stripMargin
      )
      // The new config has been picked up.
      _ <- assertCompletion(
        "override def failure@@",
        """|def failure: u.Failure[Int]
           |""".stripMargin,
        includeDetail = false
      )
      // The default settings are no longer enabled.
      _ <- assertCompletion(
        "override def set@@",
        """|def set: scala.collection.mutable.Set[Int]
           |""".stripMargin,
        includeDetail = false
      )
      _ <- assertCompletion(
        "override def list@@",
        """|def list: java.util.List[Int]
           |""".stripMargin,
        includeDetail = false
      )
    } yield ()
  }

  test("rambo".flaky) {
    cleanWorkspace()
    for {
      _ <- server.initialize(
        """|/a/src/main/scala/a/A.scala
           |object Main extends App {
           |  // @@
           |}
           |""".stripMargin,
        expectError = true
      )
      _ <- assertCompletion(
        "Properties@@",
        // Assert both JDK and scala-library are indexed.
        """|Properties - java.util
           |Properties - scala.util
           |""".stripMargin,
        filter = _.startsWith("Properties -")
      )
    } yield ()
  }
}

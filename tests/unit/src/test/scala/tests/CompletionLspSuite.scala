package tests

import scala.concurrent.Future

import scala.meta.internal.metals.{BuildInfo => V}

import munit.Location

class CompletionLspSuite extends BaseCompletionLspSuite("completion") {

  override def munitIgnore: Boolean = isWindows

  test("basic-213") {
    basicTest(V.scala213)
  }

  test("workspace".flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
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
           |""".stripMargin,
      )
    } yield ()
  }

  def checkPlugin(
      name: String,
      compilerPlugins: String,
      extra: => Future[Unit] = Future.successful(()),
  )(implicit loc: Location): Unit =
    test(name) {
      for {
        _ <- initialize(
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
          """|DelayedLazyVal - scala.concurrent
             |""".stripMargin,
        )
        _ <- extra
      } yield ()
    }

  checkPlugin(
    "empty",
    "",
  )

  // FIXME(gabro): the tests don't pass with 2.12.10, although the plugins seem to work fine when
  // tested manually
  // It's also not published for 2.13
  if (
    BuildInfo.scalaVersion != "2.12.10" && !BuildInfo.scalaVersion.startsWith(
      "2.13"
    )
  ) {
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
          "fold[C](fa: Int => C, fb: String => C): C",
        )
      } yield (),
    )

    checkPlugin(
      "better-monadic-for",
      """|
         |"com.olegpy::better-monadic-for:0.3.0"
         |""".stripMargin,
      for {
        _ <- assertCompletion(
          """|  for (implicit0(x: String) <- Option(""))
             |    implicitly[String].toCharArr@@
             |""".stripMargin,
          """|toCharArray(): Array[Char]
             |""".stripMargin,
        )
      } yield (),
    )
  }

  test("symbol-prefixes") {
    cleanWorkspace()
    for {
      _ <- initialize(
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
        includeDetail = false,
      )
      _ <- assertCompletion(
        "override def list@@",
        """|def list: ju.List[Int]
           |""".stripMargin,
        includeDetail = false,
      )
      _ <- assertCompletion(
        "override def failure@@",
        """|def failure: Failure[Int]
           |""".stripMargin,
        includeDetail = false,
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
        includeDetail = false,
      )
      // The default settings are no longer enabled.
      _ <- assertCompletion(
        "override def set@@",
        """|def set: mutable.Set[Int]
           |""".stripMargin,
        includeDetail = false,
      )
      _ <- assertCompletion(
        "override def list@@",
        """|def list: java.util.List[Int]
           |""".stripMargin,
        includeDetail = false,
      )
    } yield ()
  }

  test("rambo".flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/a/src/main/scala/a/A.scala
           |object Main extends App {
           |  // @@
           |}
           |""".stripMargin,
        expectError = true,
      )
      _ <- assertCompletion(
        "Properties@@",
        // Assert both JDK and scala-library are indexed.
        """|Properties - java.util
           |Properties - scala.util
           |""".stripMargin,
        filter = _.startsWith("Properties -"),
      )
    } yield ()
  }

  test("with-exclusions") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |object Main {
          |  // @@
          |}
          |""".stripMargin
      )
      _ <- assertCompletion(
        "Duration@@",
        """|Duration - java.time
           |Duration - javax.xml.datatype
           |Duration - scala.concurrent.duration
           |DurationDouble - scala.concurrent.duration.package
           |DurationDouble - scala.concurrent.duration.package
           |DurationInt - scala.concurrent.duration.package
           |DurationInt - scala.concurrent.duration.package
           |DurationIsOrdered - scala.concurrent.duration.Duration
           |DurationLong - scala.concurrent.duration.package
           |DurationLong - scala.concurrent.duration.package
           |FiniteDuration - scala.concurrent.duration
           |JavaDurationOps - scala.jdk.DurationConverters
           |JavaDurationOps - scala.jdk.DurationConverters
           |ScalaDurationOps - scala.jdk.DurationConverters
           |ScalaDurationOps - scala.jdk.DurationConverters""".stripMargin,
        includeDetail = false,
      )
      _ <- server.didChangeConfiguration(
        """{
          |  "excluded-packages": [
          |    "scala.concurrent"
          |  ]
          |}
          |""".stripMargin
      )
      // The new config has been picked up and all `scala.concurrent` are no longer suggested
      _ <- assertCompletion(
        "Duration@@",
        """|Duration - java.time
           |Duration - javax.xml.datatype
           |DurationConverters - scala.jdk
           |DurationConverters - scala.jdk.javaapi
           |JavaDurationOps - scala.jdk.DurationConverters
           |JavaDurationOps - scala.jdk.DurationConverters
           |ScalaDurationOps - scala.jdk.DurationConverters
           |ScalaDurationOps - scala.jdk.DurationConverters
           |""".stripMargin,
        includeDetail = false,
      )
    } yield ()
  }

  test("symbolic-name") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |
          |abstract class Base {
          |  /**
          |    * Some documentation
          |    */
          |  type !![A, B] = A with B
          |}
          |object Main extends Base {
          |  // @@
          |}
          |""".stripMargin
      )
      _ <- assertCompletionItemResolve(
        "val x = List(1).:::@@",
        expectedLabel = ":::[B >: Int](prefix: List[B]): List[B]",
        expectedDoc = Some(
          """|Adds the elements of a given list in front of this list.
             |
             |Example:
             |
             |```
             |List(1, 2) ::: List(3, 4) = List(3, 4).:::(List(1, 2)) = List(1, 2, 3, 4)
             |```
             |**Parameters**
             |- `prefix`: The list elements to prepend.
             |
             |**Returns:** a list resulting from the concatenation of the given
             |   list `prefix` and this list.
             |""".stripMargin
        ),
      )
      _ <- assertCompletionItemResolve(
        "val x: !!@@",
        expectedLabel = "!!",
        expectedDoc = Some("Some documentation"),
      )
    } yield ()
  }

  test("java") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/java/a/A.java
          |package a;
          |
          |public class A {
          |  String name = "";
          |}
          |/a/src/main/java/a/B.java
          |package a;
          |
          |public class B {
          |  A a = new A();
          |  public void main(){
          |    // @@
          |  }
          |}
          |""".stripMargin
      )
      _ <- server.didSave("a/src/main/java/a/B.java")(identity)
      _ <- assertCompletion(
        "a.n@@",
        """|name java.lang.String
           |notify() void
           |notifyAll() void
           |""".stripMargin,
        filename = Some("a/src/main/java/a/B.java"),
      )
    } yield ()
  }
}

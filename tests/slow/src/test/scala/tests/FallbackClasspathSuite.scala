package tests

import scala.meta.internal.metals

class FallbackClasspathSuite
    extends BaseLspSuite("fallback-classpath")
    with BaseSourcePathSuite {
  test("basic") {
    cleanWorkspace()
    val a = "A.scala"
    val b = "B.java"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {"libraryDependencies": ["com.google.code.gson:gson:2.10.1"]}
            |}
            |/${a}
            |package a
            |object A {
            |  val gson = new com.google.gson.Gson()
            |}
            |/${b}
            |package b;
            |public class B {
            |  public static void main() {
            |    var gson = new com.google.gson.Gson();
            |  }
            |}
            |
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      _ <- server.didOpenAndFocus(b)
      _ = assertNoDiagnostics()
    } yield ()
  }

  test("scala-version") {
    cleanWorkspace()
    val a = "A.scala"
    for {
      _ <- initialize(
        s"""|
            |/metals.json
            |{
            |  "a": {
            |    "scalaVersion": "${metals.BuildInfo.scala212}",
            |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.4.4"]
            |  },
            |  "b": {
            |    "scalaVersion": "${metals.BuildInfo.scala213}",
            |    "libraryDependencies": ["com.lihaoyi::sourcecode:0.4.4"]
            |  }
            |}
            |/${a}
            |package a
            |object A {
            |  // collection.View was introduced in 2.13
            |  val CI: collection.View[Int] = List(1).view
            |  // Line.generate is a macro, so crashes if not paired with the right
            |  // scala-compiler version.
            |  val line = sourcecode.Line.generate
            |}
            |""".stripMargin
      )
      _ <- server.didOpenAndFocus(a)
      // Assert we used 2.13 as the fallback compiler version (because no error
      // related to `collection.View`), AND assert that com.lihayoyi:sourcecode
      // is on the fallback compiler classpath because it's a dependency of
      // project "a".
      _ = assertNoDiagnostics()
      _ <- server.didChangeConfiguration(
        s"""|
            |{
            |  "fallbackScalaVersion": "${metals.BuildInfo.scala212}",
            |  "fallbackClasspath": ["all-3rdparty"]
            |}
            |""".stripMargin
      )
      _ <- server.didFocus(a)
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        // Assert that we're definitely using 2.12 now, but there's no error
        // related to com.lihaoyi:sourcecode meaning we picked up the 2.12
        // version of com.lihaoyi:sourcecode. If we had picked the 2.13 version
        // of com.lihaoyi:sourcecode then the compiler would crash because this is
        // a macro.
        """|A.scala:4:11: error: type View is not a member of package collection
           |  val CI: collection.View[Int] = List(1).view
           |          ^^^^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  // NOTE: we're not able to test the situation where we have pure Java targets
  // because quickbuild only generates Scala targets.

}

package tests

import java.util.concurrent.TimeUnit

import scala.collection.JavaConverters._
import scala.concurrent.Future

import scala.meta.internal.metals.MetalsServerConfig
import scala.meta.internal.metals.Time

class CompilersLspSuite
    extends BaseCompletionLspSuite("compilers")
    with BaseSourcePathSuite {

  var fakeTime: FakeTime = _
  override def time: Time = fakeTime
  override def beforeEach(context: BeforeEach): Unit = {
    fakeTime = new FakeTime()
    super.beforeEach(context)
  }

  override def serverConfig: MetalsServerConfig =
    MetalsServerConfig.default.copy(loglevel = "debug")

  test("reset-pc".flaky) {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {},
          |  "b": { "dependsOn": ["a"] }
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |object B {
          |  // @@
          |  def completeThisUniqueName() = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ <- Future.sequence(
        List('a', 'b').map { project =>
          assertCompletion(
            "completeThisUniqueNa@@",
            "completeThisUniqueName(): Int",
            project = project,
          )
        }
      )
      count = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(
        2,
        count,
      )
      _ <-
        server.didChange("b/src/main/scala/b/B.scala")(_ =>
          "package b; object B"
        )
      _ <-
        server.didSave("b/src/main/scala/b/B.scala")
      _ <-
        server.didChange("a/src/main/scala/a/A.scala")(_ =>
          "package a; class A"
        )
      _ <-
        server.didSave("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      countAfter = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(
        0,
        countAfter,
      )
    } yield ()
  }

  test("fallback-compiler") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  def callMeMaybe() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a.A
          |object B {
          |  def foobar() = new A().callMeMaybe("foobar")
          |}
        """.stripMargin
      )
      // trigger the fallback compiler since B is not part of a build target
      // and test that it correctly understands the source in A and issues an error
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/B.scala:4:38: error: no arguments allowed for nullary method callMeMaybe: (): Int
           |  def foobar() = new A().callMeMaybe("foobar")
           |                                     ^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("restart-on-shim-globs-update") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |object A {
          |  val abc = 1
          |  // @@
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- server.didFocus("a/src/main/scala/a/A.scala")
      count = server.server.loadedPresentationCompilerCount()
      _ = assert(clue(count) > 0)
      _ <- server.didChangeConfiguration(
        """{
          |  "shimGlobs": {
          |    "custom": ["**/my-shims/*.scala"]
          |  }
          |}
          |""".stripMargin
      )
      countAfter = server.server.loadedPresentationCompilerCount()
      _ = assertEquals(0, countAfter)
    } yield ()
  }

  test("fallback-compiler-exclusion") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  def callMeMaybe() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a.A
          |object B {
          |  def foobar() = new A().callMeMaybe()
          |}
          |/b/src/main/scala/b/C.scala
          |package b
          |import a.A
          |import experimental.Experimental
          |object C {
          |  def foobar() = new A().callMeMaybe()
          |  def foobar() = new Experimental().callMeMaybe()
          |}
          |/experimental/src/main/scala/experimental/Experimental.scala
          |package experimental
          |class Experimental {
          |  def callMeMaybe() = 42
          |}
        """.stripMargin
      )
      // trigger the fallback compiler since B is not part of a build target
      // and test that it correctly understands the source in A and issues an error
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ <- server.didOpen("b/src/main/scala/b/C.scala")
      _ <- server.didFocus("b/src/main/scala/b/C.scala")
      _ = assertNoDiff(
        client.workspaceDiagnostics,
        """|b/src/main/scala/b/C.scala:3:8: error: not found: object experimental
           |import experimental.Experimental
           |       ^^^^^^^^^^^^
           |b/src/main/scala/b/C.scala:6:22: error: not found: type Experimental
           |  def foobar() = new Experimental().callMeMaybe()
           |                     ^^^^^^^^^^^^
           |""".stripMargin,
      )
    } yield ()
  }

  test("fallback-compiler-restart-no-leak") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """
          |/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  def callMeMaybe() = 42
          |}
          |/b/src/main/scala/b/B.scala
          |package b
          |import a.A
          |object B {
          |  def foobar() = new A().callMeMaybe()
          |}
        """.stripMargin
      )
      _ <- server.didOpen("b/src/main/scala/b/B.scala")
      _ <- server.didFocus("b/src/main/scala/b/B.scala")
      _ = assertNoDiagnostics()
      _ = server.fullServer
        .getServiceFor(server.toPath("b/src/main/scala/b/B.scala"))
        .restartFallbackCompilers()
      count = countCompilerThreads("Metals/default")
      _ = assert(count < 2, s"Leaking presentation compiler threads: $count")
    } yield ()
  }

  test("evict-idle-pc") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """/metals.json
          |{
          |  "a": {}
          |}
          |/a/src/main/scala/a/A.scala
          |package a
          |class A {
          |  // @@
          |  def hello() = 42
          |}
          |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/A.scala")
      _ = assertNoDiagnostics()
      _ <- assertCompletion(
        "hell@@",
        "hello(): Int",
      )
      _ = assert(
        server.server.loadedPresentationCompilerCount() > 0,
        "expected at least one loaded presentation compiler",
      )
      // Advance time past the 30s idle timeout and trigger eviction
      _ = fakeTime.elapse(31, TimeUnit.SECONDS)
      _ = server.server.evictIdleCompilers()
      _ = assertEquals(
        server.server.loadedPresentationCompilerCount(),
        0,
        "idle compilers should have been evicted",
      )
      // Compiler should be recreated transparently on next use
      _ <- assertCompletion(
        "hell@@",
        "hello(): Int",
      )
      _ = assert(
        server.server.loadedPresentationCompilerCount() > 0,
        "compiler should be recreated after eviction",
      )
    } yield ()
  }

  private def countCompilerThreads(id: String): Int = {
    val threads = Thread.getAllStackTraces().keySet.asScala
    threads.count(
      _.getName.contains(
        s"Scala Presentation Compiler w/o backgroundCompile[$id]"
      )
    )
  }
}

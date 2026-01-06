package tests

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections.emptyList
import java.util.Collections.singletonList

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

import scala.meta.internal.builds.ShellRunner
import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.DebugSession
import scala.meta.internal.metals.DebugUnresolvedAttachRemoteParams
import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JdkSources
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.ServerCommands.StartAttach
import scala.meta.internal.metals.debug.DiscoveryFailures._
import scala.meta.internal.metals.debug.Stoppage
import scala.meta.internal.metals.debug.TestDebugger

import ch.epfl.scala.bsp4j.DebugSessionParamsDataKind
import ch.epfl.scala.bsp4j.ScalaMainClass
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class DebugProtocolSuite
    extends BaseDapSuite(
      "debug-protocol",
      QuickBuildInitializer,
      QuickBuildLayout,
    ) {

  test("start") {
    val mainClass = new ScalaMainClass(
      "a.Main",
      List("Bar").asJava,
      List("-Dproperty=Foo").asJava,
    )
    mainClass.setEnvironmentVariables(List("HELLO=Foo").asJava)
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    val foo = sys.props.getOrElse("property", "")
           |    val bar = args(0)
           |    val env = sys.env.get("HELLO")
           |    print(foo + bar)
           |    env.foreach(print)
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        mainClass,
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "FooBarFoo")
  }

  test("attach") {
    val port = 5566
    def runningMain() = Future {
      val classpathJar = workspace.resolve(".metals/.tmp").list.head.toString()
      ShellRunner.runSync(
        List(
          JdkSources.defaultJavaHome(None).head.resolve("bin/java").toString,
          "-Dproperty=Foo",
          s"-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=$port",
          "-cp",
          classpathJar,
          "a.AttachingMain",
          "Bar",
        ),
        workspace,
        true,
        Map("HELLO" -> "Foo"),
        propagateError = true,
        timeout = 60.seconds,
      )
    }

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object AttachingMain {
           |  def main(args: Array[String]) = {
           |    val foo = sys.props.getOrElse("property", "")
           |    val bar = args(0)
           |    val env = sys.env.get("HELLO")
           |    print(foo + bar)
           |    env.foreach(print)
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.headServer.buildServerPromise.future
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didSave("a/src/main/scala/a/Main.scala")
      // creates a classpath jar that we can use
      _ <- server
        .executeCommand(
          ServerCommands.DiscoverMainClasses,
          new DebugDiscoveryParams(
            null,
            "run",
            "a.AttachingMain",
          ),
        )
      runMain = runningMain()
      debugSession <- server.executeCommand(
        StartAttach,
        DebugUnresolvedAttachRemoteParams("localhost", port),
      )
      debugger = debugSession match {
        case DebugSession(_, uri) =>
          scribe.info(s"Starting debug session for $uri")
          TestDebugger(
            URI.create(uri),
            Stoppage.Handler.Continue,
            requestOtherThreadStackTrace = false,
          )
        case _ => throw new RuntimeException("Debug session not found")
      }
      _ <- debugger.initialize
      _ <- debugger.attach(port)
      _ <- debugger.configurationDone
      output <- runMain
      _ <- debugger.disconnect
      _ <- debugger.shutdown
      _ <- debugger.allOutput
    } yield assertNoDiff(
      output.getOrElse(""),
      s"""|Listening for transport dt_socket at address: $port
          |FooBarFoo""".stripMargin,
    )
  }

  test("broken-workspace") {
    cleanWorkspace()

    def startDebugging() =
      server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass("a.Main", Nil.asJava, Nil.asJava),
      )
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    println("Hello world")
           |    System.exit(0)
           |  }
           |
           |""".stripMargin
      )
      failed = startDebugging()
      debugger <- failed.recoverWith { case _: ResponseErrorException =>
        for {
          _ <- server
            .didChange("a/src/main/scala/a/Main.scala") { text =>
              text + "}"
            }
          _ <- server
            .didSave("a/src/main/scala/a/Main.scala")
          start <- startDebugging()
        } yield start

      }
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Hello world")
  }

  test("disconnect") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    synchronized(wait())
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass("a.Main", emptyList(), emptyList()),
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.disconnect
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "")
  }

  test("restart") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    println("Foo")
           |    synchronized(wait())
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebugging(
        "a",
        DebugSessionParamsDataKind.SCALA_MAIN_CLASS,
        new ScalaMainClass("a.Main", emptyList(), emptyList()),
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.awaitOutput("Foo\n")

      _ <- server.didChange("a/src/main/scala/a/Main.scala")(
        _.replace("Foo", "Bar").replace("synchronized(wait())", "")
      )
      _ <- server.didSave("a/src/main/scala/a/Main.scala")
      _ <- debugger.restart
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.awaitOutput("Bar\n")
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Bar\n")
  }

  test("run-unresolved-params") {
    cleanCompileCache("a")
    cleanWorkspace()
    val envFile: Path =
      Files.write(
        workspace
          .resolve(Random.alphanumeric.take(10).mkString.toLowerCase + ".env")
          .toNIO,
        "MIDDLE_NAME=Emily\n#comment\nLAST_NAME=Morris".getBytes(),
      )

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    val name = sys.props.getOrElse("name", "")
           |    val location = args(0)
           |    val greeting = sys.env("GREETING")
           |    val middleName = sys.env("MIDDLE_NAME")
           |    val lastName = sys.env("LAST_NAME")
           |    print(s"$$greeting $$name $$middleName $$lastName from $$location")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedMainClassParams(
          "a.Main",
          "a",
          singletonList("Arkansas"),
          singletonList("-Dname=Megan"),
          Map("GREETING" -> "Welcome", "MIDDLE_NAME" -> "Olivia").asJava,
          envFile.getFileName.toString,
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Welcome Megan Olivia Morris from Arkansas")
  }

  test("run-unresolved-params-absolute-envfile") {
    cleanCompileCache("a")
    cleanWorkspace()
    val tmpPath = Files.createTempFile("", ".env")
    tmpPath.toFile.deleteOnExit()
    val envFile: Path =
      Files.write(tmpPath, "MIDDLE_NAME=Emily\nLAST_NAME=Morris".getBytes())

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    val name = sys.props.getOrElse("name", "")
           |    val location = args(0)
           |    val greeting = sys.env("GREETING")
           |    val middleName = sys.env("MIDDLE_NAME")
           |    val lastName = sys.env("LAST_NAME")
           |    print(s"$$greeting $$name $$middleName $$lastName from $$location")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedMainClassParams(
          "a.Main",
          "a",
          singletonList("Arkansas"),
          singletonList("-Dname=Megan"),
          Map("GREETING" -> "Welcome").asJava,
          envFile.toString,
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Welcome Megan Emily Morris from Arkansas")
  }

  test("test-unresolved-params-absolute-envfile") {
    cleanCompileCache("a")
    cleanWorkspace()
    val tmpPath = Files.createTempFile("", ".env")
    tmpPath.toFile.deleteOnExit()
    val envFile: Path =
      Files.write(tmpPath, "MIDDLE_NAME=Emily\nLAST_NAME=Morris".getBytes())

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {
           |    val name = sys.props.getOrElse("name", "")
           |    val greeting = sys.env("GREETING")
           |    val middleName = sys.env("MIDDLE_NAME")
           |    val lastName = sys.env("LAST_NAME")
           |    print(s"$$greeting $$name $$middleName $$lastName")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Foo.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams(
          "a.Foo",
          "a",
          singletonList("-Dname=Megan"),
          Map("GREETING" -> "Welcome").asJava,
          envFile.toString,
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield {
      assertContains(output, "1 tests, 1 passed")
      assertContains(output, "All tests in a.Foo passed")
      assertContains(output, "Welcome Megan Emily Morris")
    }
  }

  test("run-unrelated-error") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {},
           |  "c": {"dependsOn": ["a"]}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print(args(0))
           |    System.exit(0)
           |  }
           |}
           |/c/src/main/scala/c/Other.scala
           |package c
           |object Other {
           |  val a : Int = ""
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didOpen("c/src/main/scala/c/Other.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedMainClassParams(
          "a.Main",
          "a",
          singletonList("Foo"),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "Foo")
  }

  test("abort-run-broken-workspace") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {"dependsOn": ["c"]},
           |  "c": {}
           |}
           |/a/src/main/scala/a/Main.scala
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    println(c.Other.a)
           |    System.exit(0)
           |  }
           |}
           |/c/src/main/scala/c/Other.scala
           |package c
           |object Other {
           |  val a : Int = 1
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Main.scala")
      _ <- server.didChange("c/src/main/scala/c/Other.scala") { _ =>
        """|package c
           |object Other {
           |  val a : Int = ""
           |}""".stripMargin
      }
      _ <- server.didSave("c/src/main/scala/c/Other.scala")
      result <-
        server
          .startDebuggingUnresolved(
            new DebugUnresolvedMainClassParams(
              "a.Main",
              "a",
              singletonList("Foo"),
            ).toJson
          )
          .recover { case e: ResponseErrorException =>
            (e.getResponseError().getCode(), e.getMessage())
          }
    } yield assertNoDiff(
      result.toString(),
      """
        |(543,Cannot run class, since the workspace has errors.)
      """.stripMargin,
    )
  }

  test("test-unresolved-params") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("a/src/main/scala/a/Foo.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams(
          "a.Foo"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(output.contains("All tests in a.Foo passed"))
  }

  test("abort-test-broken-workspace") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/a/Foo.scala")
      _ <- server.didChange("a/src/main/scala/a/Foo.scala") { _ =>
        """|package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {
           |    val a : Int = ""
           |  }
           |}""".stripMargin
      }
      _ <- server.didSave("a/src/main/scala/a/Foo.scala")
      result <-
        server
          .startDebuggingUnresolved(
            new DebugUnresolvedTestClassParams(
              "a.Foo"
            ).toJson
          )
          .recover { case e: ResponseErrorException =>
            e.getMessage()
          }
    } yield assertNoDiff(
      result.toString(),
      WorkspaceErrorsException.getMessage(),
    )
  }

  test("assert-location") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {
           |    println("foo")
           |    println("foo")
           |    println("foo")
           |    assert(1 == 2)
           |  }
           |}
           |""".stripMargin
      )
      debugger <-
        server
          .startDebuggingUnresolved(
            new DebugUnresolvedTestClassParams(
              "a.Foo"
            ).toJson
          )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allTestEvents
    } yield assertNoDiff(
      output,
      """|a.Foo
         |  foo - failed at Foo.scala, line 6
         |""".stripMargin,
    )
  }
  test("duplicate-output") {
    cleanCompileCache("a")
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/a/src/main/scala/a/Foo.scala
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {
           |    println("Output from test")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.server.indexingPromise.future
      _ <- server.didOpen("a/src/main/scala/a/Foo.scala")
      debugger <- server.startDebuggingUnresolved(
        new DebugUnresolvedTestClassParams(
          "a.Foo"
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield {
      val count = output.split("\n").count(_.contains("Output from test"))
      assert(
        count >= 2,
        s"Expected at least 2 occurrences of 'Output from test', found $count. Output:\n$output",
      )
    }
  }
}

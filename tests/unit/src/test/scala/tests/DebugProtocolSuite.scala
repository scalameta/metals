package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections.emptyList
import java.util.Collections.singletonList

import scala.util.Random

import scala.meta.internal.metals.DebugUnresolvedMainClassParams
import scala.meta.internal.metals.DebugUnresolvedTestClassParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals.debug.DiscoveryFailures._

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

  test("disconnect".flaky) {
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
    } yield assertContains(
      output,
      """|1 tests, 1 passed
         |All tests in a.Foo passed
         |
         |Welcome Megan Emily Morris
         |""".stripMargin,
    )
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
            e.getMessage()
          }
    } yield assertNoDiff(
      result.toString(),
      WorkspaceErrorsException.getMessage(),
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
}

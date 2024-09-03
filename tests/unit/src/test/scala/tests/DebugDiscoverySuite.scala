package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.jdk.CollectionConverters.MapHasAsJava
import scala.util.Random

import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.ServerCommands
import scala.meta.internal.metals.debug.DiscoveryFailures._
import scala.meta.internal.metals.debug.DotEnvFileParser.InvalidEnvFileException
import scala.meta.internal.metals.debug.ExtendedScalaMainClass
import scala.meta.io.AbsolutePath

import ch.epfl.scala.bsp4j.DebugSessionParams
import com.google.gson.JsonObject
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException

// note(@tgodzik) all test have `System.exit(0)` added to avoid occasional issue due to:
// https://stackoverflow.com/questions/2225737/error-jdwp-unable-to-get-jni-1-2-environment
class DebugDiscoverySuite
    extends BaseDapSuite(
      "debug-discovery",
      QuickBuildInitializer,
      QuickBuildLayout,
    ) {
  private val mainPath = "a/src/main/scala/a/Main.scala"
  private val fooPath = "a/src/main/scala/a/Foo.scala"
  private val barPath = "a/src/main/scala/a/Bar.scala"
  private val altTargetPath = "b/src/main/scala/b/Main.scala"

  test("run") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print("oranges are nice")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(mainPath).toURI.toString,
          "run",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "oranges are nice")
  }

  test("discover-class-run") {
    import scala.meta.internal.metals.JsonParser._
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print("oranges are nice")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      result <- server
        .executeCommand(
          ServerCommands.DiscoverMainClasses,
          new DebugDiscoveryParams(
            null,
            "run",
            "a.Main",
          ),
        )
        .map(_.asInstanceOf[DebugSessionParams])
      mainClass =
        result.getData().asInstanceOf[JsonObject].as[ExtendedScalaMainClass]
      _ = assert(
        mainClass.isSuccess,
        "Server should return ExtendedScalaMainClass object",
      )
    } yield assert(
      mainClass.get.shellCommand.nonEmpty,
      "Shell command should be available in response for discovery",
    )
  }

  test("run-file-main") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print("oranges are nice")
           |    System.exit(0)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(mainPath).toURI.toString,
          "runOrTestFile",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "oranges are nice")
  }

  test("run-file-test") {
    val envFile: Path =
      Files.write(
        workspace
          .resolve(Random.alphanumeric.take(10).mkString.toLowerCase + ".env")
          .toNIO,
        "BAR=BAZ".getBytes(),
      )
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {
           |    val foo = sys.env("FOO")
           |    val bar = sys.env("BAR")
           |    print(s"$$foo $$bar")
           |}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runOrTestFile",
          env = Map("FOO" -> "BAR").asJava,
          envFile = envFile.getFileName.toString,
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains(
        """|All tests in a.Foo passed
           |
           |BAR BAZ
           |""".stripMargin
      )
    )
  }

  test("no-run-or-test") {
    val notATestPath = "a/src/main/scala/a/NotATest.scala"
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${notATestPath}
           |package a
           |class NotATest {
           |    print("I'm not a test!")
           |    System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(notATestPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(notATestPath).toURI.toString,
            "runOrTestFile",
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString(),
      NoRunOptionException.getMessage(),
    )
  }

  test("run-multiple") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Main extends App {
           |    print("what about grapes?")
           |    System.exit(0)
           |}
           |
           |object Other extends App {
           |    print("o, and pears!")
           |    System.exit(0)
           |}
           |""".stripMargin
      )
      // TestingClient handles the choice here to pick a.Main
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(mainPath).toURI.toString,
          "run",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "what about grapes?")
  }

  test("no-main") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Stuff {
           |  val stuff = "you guessed it, stuff"
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(mainPath).toURI.toString,
            "run",
            buildTarget = "a",
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString,
      BuildTargetContainsNoMainException("a").getMessage(),
    )
  }

  test("workspace-error") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Stuff {
           |  val stuff = "you guessed it, stuff... but with no ending!
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(mainPath).toURI.toString,
            "run",
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString,
      WorkspaceErrorsException.getMessage(),
    )
  }

  test("other-target-error") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {},
           |  "b": {}
           |}
           |/${mainPath}
           |package a
           |object Main {
           |  def main(args: Array[String]) = {
           |    print("oranges are nice")
           |    System.exit(0)
           |  }
           |}
           |/${altTargetPath}
           |package b
           |object Stuff {
           |  val stuff = "fail :(
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(mainPath).toURI.toString,
          "run",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(output, "oranges are nice")
  }

  test("invalid-env") {
    val fakePath = workspace.toString + "fake-path"

    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${mainPath}
           |package a
           |object Stuff extends App {
           |  val veryHardCode = "wowza"
           |  System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(mainPath)
      _ <- server.didSave(mainPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            path = server.toPath(mainPath).toURI.toString,
            runType = "run",
            envFile = fakePath,
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString,
      InvalidEnvFileException(AbsolutePath(fakePath)).getMessage(),
    )
  }

  test("testFile") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "testFile",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(output.contains("All tests in a.Foo passed"))
  }

  test("testTarget") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {}
           |}
           |/${barPath}
           |package a
           |class Bar extends org.scalatest.funsuite.AnyFunSuite {
           |  test("bart") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(barPath)
      _ <- server.didSave(barPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(barPath).toURI.toString,
          "testTarget",
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(output.contains("All tests in a.Foo passed"))
  }

  test("no-tests") {
    val notATestPath = "a/src/main/scala/a/NotATest.scala"
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${notATestPath}
           |package a
           |class NotATest {
           |    print("I'm not a test!")
           |    System.exit(0)
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(notATestPath)
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(notATestPath).toURI.toString,
            "testTarget",
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString(),
      NoTestsFoundException("build target", "a").getMessage(),
    )
  }

  test("no-semanticdb") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  }
           |}
           |/${fooPath}
           |package a
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("foo") {}
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)(identity)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      _ = cleanCompileCache("a")
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(fooPath).toURI.toString,
            "testFile",
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString,
      SemanticDbNotFoundException.getMessage(),
    )
  }
}

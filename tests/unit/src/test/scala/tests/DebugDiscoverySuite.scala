package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.util.Random

import scala.meta.internal.metals.DebugDiscoveryParams
import scala.meta.internal.metals.JsonParser._
import scala.meta.internal.metals.MetalsEnrichments._
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
           |    val argsString = args.mkString
           |    print("oranges are nice " + argsString)
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
            args = List("Hello", " Wo$rld").asJava,
          ),
        )
        .map(_.asInstanceOf[DebugSessionParams])
      mainClass =
        result.getData().asInstanceOf[JsonObject].as[ExtendedScalaMainClass]
      _ = assert(
        mainClass.isSuccess,
        "Server should return ExtendedScalaMainClass object",
      )
      _ = assert(
        mainClass.get.shellCommand.nonEmpty,
        "Shell command should be available in response for discovery",
      )
    } yield {
      import scala.sys.process._
      val output = mainClass.get.shellCommand.!!
      val expected =
        if (isWindows) "oranges are nice Hello Wo$rld"
        else "oranges are nice Hello Wo\\$rld"
      assertEquals(output.trim(), expected)
    }
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
      _ <- server.didSave(fooPath)
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
      _ <- server.didSave(mainPath)
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
      _ <- server.didSave(fooPath)
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
      _ <- server.didSave(barPath)
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
      _ <- server.didSave(fooPath)
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

  test("main-method-in-dependencies") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalatest::scalatest:3.2.16"]
           |  },
           |  "b": {}
           |}
           |""".stripMargin
      )
      discovery <- server.executeDiscoverMainClassesCommand(
        new DebugDiscoveryParams(
          path = null,
          runType = "run",
          mainClass = "org.scalatest.tools.Runner",
        )
      )
    } yield {
      assertEquals(
        discovery.getTargets().asScala.map(_.getUri()).toList,
        List(server.buildTarget("a")),
      )
      discovery.asScalaMainClass match {
        case Left(err) => sys.error(s"Expected Main class ($err)")
        case Right(scalaMainClass) =>
          assertNoDiff(
            scalaMainClass.getClassName(),
            "org.scalatest.tools.Runner",
          )
      }
    }
  }

  test("run-closest-test") {
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
           |
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("first") {
           |    print("first test")
           |  }
           |  test("second") {
           |    print("second test") // <- Cursor in this line
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(7, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("second test") &&
        !output.contains("first test") &&
        output.contains("1 tests, 1 passed")
    )
  }

  test("run-closest-suite") {
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
           |
           |class Foo extends org.scalatest.funsuite.AnyFunSuite { // <- Cursor in this line
           |  test("first") {
           |    print("first test")
           |  }
           |  test("second") {
           |    print("second test")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(2, 4),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("first test") &&
        output.contains("second test") &&
        output.contains("2 tests, 2 passed")
    )
  }

  test("run-closest-multiple-suites") {
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
           |
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  test("Foo-first") {
           |    print("Foo-first")
           |  }
           |  test("Foo-second") {
           |    print("Foo-second")
           |  }
           |} // <- Cursor in this line
           |class Bar extends org.scalatest.funsuite.AnyFunSuite {
           |  test("Bar-first") {
           |    print("Bar-first")
           |  }
           |  test("Bar-second") {
           |    print("Bar-second")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(9, 10),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("Bar-first") &&
        output.contains("Bar-second") &&
        output.contains("2 tests, 2 passed")
    )
  }

  test("run-closest-tie-breaker") {
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
           |
           |class Foo extends org.scalatest.funsuite.AnyFunSuite {
           |  // <- Cursor in this line
           |test("first") {
           |  print("first test")
           |}
           |  test("second") {
           |    print("second test")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(3, 0),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      (output.contains("first test") &&
        !output.contains("second test") &&
        output.contains("1 tests, 1 passed"))
    )
  }

  test("run-closest-munit") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalameta::munit:1.0.0-M11"]
           |  }
           |}
           |/${fooPath}
           |package a
           |
           |class FooMUnitTest extends munit.FunSuite {
           |  test("munit first test") {
           |    print("first print")
           |  }
           |  test("munit second test") {
           |    print("second print") // <- Cursor in this line
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(7, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("second print") &&
        !output.contains("first print") &&
        output.contains("2 tests, 1 passed, 1 ignored")
    )
  }

  test("run-closest-munit-suite") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["org.scalameta::munit:1.0.0-M11"]
           |  }
           |}
           |/${fooPath}
           |package a
           |
           |class FooMUnitTest extends munit.FunSuite { // <- Cursor in this line
           |  test("munit first test") {
           |    print("munit first")
           |  }
           |  test("munit second test") {
           |    print("munit second")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(2, 4),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("munit first") &&
        output.contains("munit second") &&
        output.contains("2 passed")
    )
  }

  test("run-closest-junit") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":["junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3"]
           |  }
           |}
           |/${fooPath}
           |package a
           |
           |import org.junit.Test
           |import org.junit.Assert._
           |
           |class FooJUnitTest {
           |  @Test
           |  def junitFirstTest(): Unit = {
           |    print("junit first")
           |  }
           |  
           |  @Test
           |  def junitSecondTest(): Unit = { 
           |    print("junit second")
           |  } // <- Cursor in this line
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(14, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("junit second") &&
        !output.contains("junit first")
    )
  }

  test("run-closest-mixed-frameworks") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "libraryDependencies":[
           |      "org.scalatest::scalatest:3.2.16",
           |      "org.scalameta::munit:1.0.0-M11"
           |    ]
           |  }
           |}
           |/${fooPath}
           |package a
           |
           |class ScalaTestSuite extends org.scalatest.funsuite.AnyFunSuite {
           |  test("scalatest test") {
           |    print("scalatest first")
           |  }
           |}
           |
           |class MUnitSuite extends munit.FunSuite {
           |  test("munit test") {
           |    print("munit first") // <- Cursor in this line
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(10, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("munit first") &&
        !output.contains("scalatest first")
    )
  }

  test("run-closest-empty-test-file") {
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
           |
           |class EmptyTestSuite extends org.scalatest.funsuite.AnyFunSuite {
           |  // No tests defined
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      result <- server
        .startDebuggingUnresolved(
          new DebugDiscoveryParams(
            server.toPath(fooPath).toURI.toString,
            "runClosest",
            position = new org.eclipse.lsp4j.Position(4, 5),
          ).toJson
        )
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString(),
      NoRunOptionException.getMessage(),
    )
  }

  test("run-closest-main") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${fooPath}
           |package a
           |
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    print("main executed") // <- Cursor in this line
           |  }
           |}
           |
           |object OtherMain {
           |  def main(args: Array[String]): Unit = {
           |    print("other main executed")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(4, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("main executed") &&
        !output.contains("other main executed")
    )
  }

  test("run-closest-mixed-main-and-test") {
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
           |
           |object Main {
           |  def main(args: Array[String]): Unit = {
           |    print("main executed")
           |  }
           |}
           |
           |class TestSuite extends org.scalatest.funsuite.AnyFunSuite {
           |  test("test case") { // <- Cursor in this line
           |    print("test executed")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(9, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("test executed") &&
        !output.contains("main executed")
    )
  }

  test("run-closest-scala3-main-annotation") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {
           |    "scalaVersion": "3.3.0"
           |  }
           |}
           |/${fooPath}
           |package a
           |
           |@main def runApp(): Unit = {
           |  println("Scala 3 main executed") // <- Cursor in this line
           |}
           |
           |@main def runOtherApp(): Unit = {
           |  println("Other Scala 3 main executed")
           |}
           |
           |class SomeClass {
           |  def regularMethod(): Unit = {
           |    println("not a main")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(3, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("Scala 3 main executed") &&
        !output.contains("Other Scala 3 main executed") &&
        !output.contains("not a main")
    )
  }

  test("run-closest-app-trait-fallback") {
    for {
      _ <- initialize(
        s"""/metals.json
           |{
           |  "a": {}
           |}
           |/${fooPath}
           |package a
           |
           |object MyApp extends App {
           |  println("App trait main executed") // <- Cursor in this line
           |}
           |
           |class SomeClass {
           |  def someMethod(): Unit = {
           |    println("not main")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(fooPath)
      _ <- server.didSave(fooPath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
      debugger <- server.startDebuggingUnresolved(
        new DebugDiscoveryParams(
          server.toPath(fooPath).toURI.toString,
          "runClosest",
          position = new org.eclipse.lsp4j.Position(3, 5),
        ).toJson
      )
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assert(
      output.contains("App trait main executed") &&
        !output.contains("not main")
    )
  }
}

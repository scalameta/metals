package tests

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

import scala.concurrent.Future
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

  /**
   * Normalizes test output by removing ANSI color codes, execution times,
   * test framework dots, and trailing whitespace to make output comparison stable.
   */
  def normalizeOutput(output: String): String =
    output
      .replaceAll("\u001B\\[[;\\d]*m", "")
      .replaceAll("(?m)^Execution took.*$", "")
      .replaceAll("(?m)^Total duration:.*$", "")
      .replaceAll("\\s*\\d+\\.\\d+s", "")
      .replaceAll("\\s*\\d+s", "")
      .replaceAll("[∙·]", "")
      .replaceAll("(?m)[ \t]+$", "")
      .replaceAll("(?m)^\\s*$[\n\r]{1,}", "")
      .trim

  def checkRunClosest(
      fileContent: String,
      filePath: String,
      libraryDependencies: List[String] = List.empty,
      scalaVersion: Option[String] = None,
  ): Future[DebugDiscoveryParams] = {
    val fileName = filePath.split("/").last

    val depsJson = if (libraryDependencies.nonEmpty) {
      s""""libraryDependencies":${libraryDependencies.map(dep => s""""$dep"""").mkString("[", ",", "]")},"""
    } else ""

    val scalaVersionJson = scalaVersion match {
      case Some(version) => s""""scalaVersion": "$version","""
      case None => ""
    }

    val metalsConfig = s"""/metals.json
                          |{
                          |  "a": {
                          |    $depsJson
                          |    $scalaVersionJson
                          |  }
                          |}""".stripMargin.replaceAll(",\\s*}", "}")

    for {
      _ <- initialize(
        s"""|$metalsConfig
            |/$filePath
            |$fileContent
            |""".stripMargin
      )
      _ <- server.didOpen(filePath)
      (text, params) <- server.offsetParams(
        fileName,
        fileContent,
        workspace,
      )
      _ <- server.didChange(filePath, text)
      _ <- server.didSave(filePath)
      _ <- server.waitFor(TimeUnit.SECONDS.toMillis(10))
    } yield (
      new DebugDiscoveryParams(
        server.toPath(filePath).toURI.toString,
        "runClosest",
        position = params.getPosition(),
      )
    )
  }

  def checkRunClosestSuccess(
      fileContent: String,
      filePath: String,
      expectedOutput: String,
      libraryDependencies: List[String] = List.empty,
      scalaVersion: Option[String] = None,
  ): Future[Unit] = {
    for {
      debugParams <- checkRunClosest(
        fileContent,
        filePath,
        libraryDependencies,
        scalaVersion,
      )
      debugger <- server.startDebuggingUnresolved(debugParams.toJson)
      _ <- debugger.initialize
      _ <- debugger.launch
      _ <- debugger.configurationDone
      _ <- debugger.shutdown
      output <- debugger.allOutput
    } yield assertNoDiff(
      normalizeOutput(output),
      expectedOutput,
    )
  }

  def checkRunClosestError(
      fileContent: String,
      filePath: String,
      expectedOutput: String,
      libraryDependencies: List[String] = List.empty,
      scalaVersion: Option[String] = None,
  ): Future[Unit] = {
    for {
      debugParams <- checkRunClosest(
        fileContent,
        filePath,
        libraryDependencies,
        scalaVersion,
      )
      result <- server
        .startDebuggingUnresolved(debugParams.toJson)
        .recover { case e: ResponseErrorException => e.getMessage }
    } yield assertNoDiff(
      result.toString(),
      expectedOutput,
    )
  }

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
    val fileContent =
      """|package a
         |
         |class Foo extends org.scalatest.funsuite.AnyFunSuite {
         |  test("first") {}
         |  test("second") {@@}
         |}
      """.stripMargin
    val expectedOutput =
      """|Foo:
         |- second
         |1 tests, 1 passed
         |All tests in a.Foo passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
         |""".stripMargin
    val libraryDependencies =
      List("org.scalatest::scalatest:3.2.16")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-suite") {
    val fileContent =
      """|package a
         |
         |class Foo extends org.scalatest.funsuite.@@AnyFunSuite {
         |  test("first") {}
         |  test("second") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|Foo:
         |- first
         |- second
         |2 tests, 2 passed
         |All tests in a.Foo passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List("org.scalatest::scalatest:3.2.16")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-multiple-suites") {
    val fileContent =
      """|package a
         |
         |class BarFoo extends org.scalatest.funsuite.AnyFunSuite {
         |  test("BarFoo-first") {}
         |  test("BarFoo-second") {}
         |
         |}                           @@
         |class Bar extends org.scalatest.funsuite.AnyFunSuite {
         |  test("Bar-first") {}
         |  test("Bar-second") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|Bar:
         |- Bar-first
         |- Bar-second
         |2 tests, 2 passed
         |All tests in a.Bar passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List("org.scalatest::scalatest:3.2.16")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-tie-breaker") {
    val fileContent =
      """|package a
         |
         |class Foo extends org.scalatest.funsuite.AnyFunSuite {
         |@@
         |test("first") {}
         |test("second") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|Foo:
         |- first
         |1 tests, 1 passed
         |All tests in a.Foo passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List("org.scalatest::scalatest:3.2.16")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-munit") {
    val fileContent =
      """|package a
         |
         |class FooMUnitTest extends munit.FunSuite {
         |  test("munit first test") {}
         |  test("munit second te@@st") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|==> i a.FooMUnitTest.munit first test ignored
         |a.FooMUnitTest:
         |  + munit second test
         |2 tests, 1 passed, 1 ignored
         |All tests in a.FooMUnitTest passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List("org.scalameta::munit:1.0.0-M11")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-munit-suite") {
    val fileContent =
      """|package a
         |@@
         |class FooMUnitTest extends munit.FunSuite {
         |  test("munit first test") {}
         |  test("munit second test") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|a.FooMUnitTest:
         |  + munit first test
         |  + munit second test
         |2 tests, 2 passed
         |All tests in a.FooMUnitTest passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List("org.scalameta::munit:1.0.0-M11")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-junit") {
    val fileContent =
      """|package a
         |
         |import org.junit.Test
         |import org.junit.Assert._
         |
         |class FooJUnitTest {
         |  @Test
         |  def junitFirstTest(): Unit = {}
         |  
         |  @Test
         |  def junitSecondTest():@@ Unit = {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|Test a.FooJUnitTest.junitFirstTest ignored
         |2 tests, 1 passed, 1 ignored
         |All tests in a.FooJUnitTest passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List("junit:junit:4.13.2", "com.github.sbt:junit-interface:0.13.3")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-mixed-frameworks") {
    val fileContent =
      """|package a
         |
         |class ScalaTestSuite extends org.scalatest.funsuite.AnyFunSuite {
         |  test("scalatest test") {}
         |}
         |       @@
         |class MUnitSuite extends munit.FunSuite {
         |  test("munit test") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|a.MUnitSuite:
         |  + munit test
         |1 tests, 1 passed
         |All tests in a.MUnitSuite passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
      """.stripMargin
    val libraryDependencies =
      List(
        "org.scalatest::scalatest:3.2.16",
        "org.scalameta::munit:1.0.0-M11",
      )
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-empty-test-file") {
    val fileContent =
      """|package a
         |
         |class EmptyTestSuite extends org.sca@@latest.funsuite.AnyFunSuite {
         |  // No tests defined
         |}
         |""".stripMargin
    val libraryDependencies =
      List("org.scalatest::scalatest:3.2.16")
    val expectedOutput = NoRunOptionException.getMessage()
    checkRunClosestError(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-main") {
    val fileContent =
      """|package a
         |
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |    pri@@ntln("main executed")
         |  }
         |}
         |
         |object OtherMain {
         |  def main(args: Array[String]): Unit = {
         |    println("other main executed")
         |  }
         |}
         |""".stripMargin
    val expectedOutput = "main executed"
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
    )
  }

  test("run-closest-mixed-main-and-test") {
    val fileContent =
      """|package a
         |
         |object Main {
         |  def main(args: Array[String]): Unit = {
         |    println("main executed")
         |  }
         |}
         |
         |class TestSuite extends org.scalatest.funsuite.AnyFunSuite {
         |  test("test cas@@e") {}
         |}
         |""".stripMargin
    val expectedOutput =
      """|TestSuite:
         |- test case
         |1 tests, 1 passed
         |All tests in a.TestSuite passed
         |================================================================================
         |All 1 test suites passed.
         |================================================================================
         |""".stripMargin
    val libraryDependencies =
      List("org.scalatest::scalatest:3.2.16")
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      libraryDependencies,
    )
  }

  test("run-closest-scala3-main-annotation") {
    val fileContent =
      """|package a
         |
         |@main def runApp(): Unit = {
         |  println("Scala @@3 main executed")
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
    val expectedOutput = "Scala 3 main executed"
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
      scalaVersion = Some("3.3.6"),
    )
  }

  test("run-closest-app-trait-fallback") {
    val fileContent =
      """|package a
         |                 @@
         |object MyApp extends App {
         |  println("App trait main executed")
         |}
         |
         |class SomeClass {
         |  def someMethod(): Unit = {
         |    println("not main")
         |  }
         |}
         |""".stripMargin
    val expectedOutput = "App trait main executed"
    checkRunClosestSuccess(
      fileContent,
      fooPath,
      expectedOutput,
    )
  }
}

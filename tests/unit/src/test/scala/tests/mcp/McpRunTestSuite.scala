package tests.mcp

import java.nio.file.Files

import scala.meta.internal.metals.MetalsServerConfig

import tests.BaseLspSuite

class McpRunTestSuite extends BaseLspSuite("mcp-test") {
  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(loglevel = "debug")

  test("basic", maxRetry = 3) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies" : ["org.scalameta::munit:1.0.0-M4"]
           |  }
           |}
           |/a/src/main/scala/a/b/c/MunitTestSuite.scala
           |package a.b
           |
           |class MunitTestSuite extends munit.FunSuite {
           |  test("test1") {
           |    println("Some string")
           |    assert(1 == 1)
           |  }
           |
           |  test("test2") {
           |    assert(1 == 2)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/a/b/c/MunitTestSuite.scala"
      )
      _ = assertNoDiagnostics()
      _ <- server.server.indexingPromise.future
      path = server.toPath("a/src/main/scala/a/b/c/MunitTestSuite.scala")

      // Test with explicit path and verbose output
      res1 <- server.headServer.mcpTestRunner
        .runTests(
          "a.b.MunitTestSuite",
          Some(path),
          None,
          verbose = true,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res1.contains("2 tests, 1 passed, 1 failed"), res1)
      // Verbose prints all output from the test suite
      _ = assert(res1.contains("Some string"), res1)

      // Test without path and non-verbose output
      res2 <- server.headServer.mcpTestRunner
        .runTests("a.b.MunitTestSuite", None, None, verbose = false) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res2.contains("2 tests, 1 passed, 1 failed"), res2)
      // Non-verbose prints only errors and summary
      _ = assert(!res2.contains("Some string"), res2)

      // Test running a single test that passes
      res3 <- server.headServer.mcpTestRunner
        .runTests(
          "a.b.MunitTestSuite",
          Some(path),
          Some("test1"),
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res3.contains("test1"), s"Should contain test1: $res3")
      _ = assert(res3.contains("2 tests, 1 passed, 0 failed, 1 skipped"), res3)

      // Test running a single test that fails
      res4 <- server.headServer.mcpTestRunner
        .runTests(
          "a.b.MunitTestSuite",
          Some(path),
          Some("test2"),
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res4.contains("test2"), s"Should contain test2: $res4")
      _ = assert(res4.contains("2 tests, 0 passed, 1 failed, 1 skipped"), res4)
    } yield ()
  }

  // Regression for https://github.com/scalameta/metals/issues/8305 — MCP test runs
  // must pass workspace `.test-jvmopts` into the forked test JVM (alongside BSP options).
  test("mcp-run-tests-respects-test-jvmopts", maxRetry = 3) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies" : ["org.scalameta::munit:1.0.0-M4"]
           |  }
           |}
           |/.test-jvmopts
           |-Dmetals.issue8305.jvmopts=from-test-jvmopts
           |/a/src/test/scala/a/JvmOptsMcpSuite.scala
           |package a
           |
           |class JvmOptsMcpSuite extends munit.FunSuite {
           |  test("sees -D from .test-jvmopts in forked mcp test jvm") {
           |    assertEquals(
           |      System.getProperty("metals.issue8305.jvmopts"),
           |      "from-test-jvmopts",
           |    )
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/test/scala/a/JvmOptsMcpSuite.scala")
      _ = assertNoDiagnostics()
      _ <- server.server.indexingPromise.future
      path = server.toPath("a/src/test/scala/a/JvmOptsMcpSuite.scala")
      result <- server.headServer.mcpTestRunner
        .runTests(
          "a.JvmOptsMcpSuite",
          Some(path),
          None,
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        result.contains("1 tests, 1 passed, 0 failed"),
        s"Expected munit to pass when forked JVM receives -D from .test-jvmopts; got:\n$result",
      )
    } yield ()
  }

  test("zio-test", maxRetry = 3) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies" : ["dev.zio::zio-test:2.0.15", "dev.zio::zio-test-sbt:2.0.15"],
           |    "testFrameworks": ["zio.test.sbt.ZTestFramework"]
           |  }
           |}
           |/a/src/test/scala/a/ZioTestSuite.scala
           |package a
           |
           |import zio.test._
           |import zio.test.Assertion._
           |
           |object ZioTestSuite extends ZIOSpecDefault {
           |  def spec = suite("ZioTestSuite")(
           |    test("test one") {
           |      assertTrue(1 + 1 == 2)
           |    },
           |    test("test two") {
           |      assertTrue(2 + 2 == 4)
           |    }
           |  )
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/test/scala/a/ZioTestSuite.scala"
      )
      _ = assertNoDiagnostics()
      _ <- server.server.indexingPromise.future
      path = server.toPath("a/src/test/scala/a/ZioTestSuite.scala")

      // Test ZIO test execution - runs all tests in the suite
      result <- server.headServer.mcpTestRunner
        .runTests("a.ZioTestSuite", Some(path), None, verbose = false) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(result.nonEmpty, s"ZIO test returned empty result: '$result'")
      _ = assert(
        result.contains("test one"),
        s"ZIO test result should contain 'test one': '$result'",
      )
      _ = assert(
        result.contains("test two"),
        s"ZIO test result should contain 'test two': '$result'",
      )

      // Test running a single ZIO test
      singleResult <- server.headServer.mcpTestRunner
        .runTests(
          "a.ZioTestSuite",
          Some(path),
          Some("test one"),
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        singleResult.nonEmpty,
        s"Single ZIO test returned empty result: '$singleResult'",
      )
      _ = assert(
        singleResult.contains("test one"),
        s"Single ZIO test result should contain 'test one': '$singleResult'",
      )
      // ZIO test framework doesn't seem to support individual test selection properly yet
      // So we just verify the test ran and contains our target test
    } yield ()
  }

  test("testng", maxRetry = 3) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies" : ["org.testng:testng:7.7.1", "com.lihaoyi:mill-contrib-testng:0.12.1"]
           |  }
           |}
           |/a/src/main/scala/a/b/TestNGSuite.scala
           |package a.b
           |
           |import org.testng.annotations.Test
           |
           |class TestNGSuite {
           |  @Test
           |  def testOK(): Unit = {
           |    println("TestNG output message")
           |    assert(true)
           |  }
           |
           |  @Test
           |  def testFail(): Unit = {
           |    println("This test will fail")
           |    assert(false)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/main/scala/a/b/TestNGSuite.scala"
      )
      _ = assertNoDiagnostics()
      _ <- server.server.indexingPromise.future
      path = server.toPath("a/src/main/scala/a/b/TestNGSuite.scala")

      // Test with explicit path and verbose output
      res1 <- server.headServer.mcpTestRunner
        .runTests(
          "a.b.TestNGSuite",
          Some(path),
          None,
          verbose = true,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assertNoDiff(
        res1.replaceAll("\\d+\\.?\\d*m?s", "x ms"),
        """|SLF4J: Failed to load class "org.slf4j.impl.StaticLoggerBinder".
           |SLF4J: Defaulting to no-operation (NOP) logger implementation
           |SLF4J: See http://www.slf4j.org/codes.html#StaticLoggerBinder for further details.
           |a.b.TestNGSuite testFail This test will fail
           |X
           |a.b.TestNGSuite testOK TestNG output message
           |+
           |===============================================
           |Command line suite
           |Total tests run: 2, Passes: 1, Failures: 1, Skips: 0
           |===============================================
           |
           |Execution took x ms
           |2 tests, 1 passed, 1 failed
           |""".stripMargin,
      )
    } yield ()
  }

  test("individual-test-execution", maxRetry = 3) {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies" : ["org.scalameta::munit:1.0.0-M4"]
           |  }
           |}
           |/a/src/test/scala/a/IndividualTestSuite.scala
           |package a
           |
           |class IndividualTestSuite extends munit.FunSuite {
           |  test("passing test") {
           |    assert(1 == 1)
           |  }
           |
           |  test("failing test") {
           |    assert(1 == 2)
           |  }
           |
           |  test("another passing test") {
           |    assert(true)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen(
        "a/src/test/scala/a/IndividualTestSuite.scala"
      )
      _ = assertNoDiagnostics()
      _ <- server.server.indexingPromise.future
      path = server.toPath("a/src/test/scala/a/IndividualTestSuite.scala")

      // Test running all tests
      allResult <- server.headServer.mcpTestRunner
        .runTests(
          "a.IndividualTestSuite",
          Some(path),
          None,
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        allResult.contains("3 tests, 2 passed, 1 failed"),
        s"All tests result: $allResult",
      )

      // Test running only the passing test
      passingResult <- server.headServer.mcpTestRunner
        .runTests(
          "a.IndividualTestSuite",
          Some(path),
          Some("passing test"),
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        passingResult.contains("passing test"),
        s"Should contain 'passing test': $passingResult",
      )
      _ = assert(
        passingResult.contains("3 tests, 1 passed, 0 failed, 2 skipped"),
        s"Should have 1 passed: $passingResult",
      )
      _ = assert(
        !passingResult.contains("failing test failed"),
        s"Should not show 'failing test' as failed: $passingResult",
      )

      // Test running only the failing test
      failingResult <- server.headServer.mcpTestRunner
        .runTests(
          "a.IndividualTestSuite",
          Some(path),
          Some("failing test"),
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        failingResult.contains("failing test"),
        s"Should contain 'failing test': $failingResult",
      )
      _ = assert(
        failingResult.contains("3 tests, 0 passed, 1 failed, 2 skipped"),
        s"Should have 1 failed: $failingResult",
      )
      _ = assert(
        !failingResult.contains("passing test passed"),
        s"Should not show 'passing test' as passed: $failingResult",
      )

      // Test running a non-existent test
      nonExistentResult <- server.headServer.mcpTestRunner
        .runTests(
          "a.IndividualTestSuite",
          Some(path),
          Some("non-existent test"),
          verbose = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        nonExistentResult.contains("3 tests, 0 passed, 0 failed, 3 skipped"),
        s"Non-existent test should skip all tests: $nonExistentResult",
      )
    } yield ()
  }

  test("repeated", maxRetry = 3) {
    cleanWorkspace()
    // Counter files let the forked test JVMs communicate how often they ran,
    // which both makes flakiness deterministic (fail only on the first run)
    // and proves stopOnFirstFailure runs the JVM exactly once.
    val flakyCounter = workspace.resolve("flaky-counter.txt")
    val failCounter = workspace.resolve("fail-counter.txt")
    val crashCounter = workspace.resolve("crash-counter.txt")
    // Interpolated into Scala string literals in the generated source, so the
    // Windows path separator must be replaced to keep the literal valid.
    val flakyCounterPath = flakyCounter.toString.replace('\\', '/')
    val failCounterPath = failCounter.toString.replace('\\', '/')
    val crashCounterPath = crashCounter.toString.replace('\\', '/')
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{
           |  "a": {
           |    "libraryDependencies" : ["org.scalameta::munit:1.0.0-M4"]
           |  }
           |}
           |/a/src/test/scala/a/RepeatedSuites.scala
           |package a
           |
           |import java.nio.file.Files
           |import java.nio.file.Paths
           |import java.nio.file.StandardOpenOption
           |
           |object RunCounter {
           |  def increment(path: String): Long = {
           |    val counter = Paths.get(path)
           |    Files.write(
           |      counter,
           |      "x".getBytes(),
           |      StandardOpenOption.CREATE,
           |      StandardOpenOption.APPEND,
           |    )
           |    Files.size(counter)
           |  }
           |}
           |
           |class FlakyOnFirstRunSuite extends munit.FunSuite {
           |  test("flaky") {
           |    val runs = RunCounter.increment("$flakyCounterPath")
           |    assert(runs > 1, "fails on first run")
           |  }
           |}
           |
           |class GreenSuite extends munit.FunSuite {
           |  test("green") {
           |    assert(1 == 1)
           |  }
           |}
           |
           |class AlwaysFailingSuite extends munit.FunSuite {
           |  test("always fails") {
           |    RunCounter.increment("$failCounterPath")
           |    assert(1 == 2)
           |  }
           |}
           |
           |class CrashingSuite extends munit.FunSuite {
           |  test("crash") {
           |    RunCounter.increment("$crashCounterPath")
           |    sys.exit(1)
           |  }
           |}
           |
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/test/scala/a/RepeatedSuites.scala")
      _ = assertNoDiagnostics()
      _ <- server.server.indexingPromise.future
      path = server.toPath("a/src/test/scala/a/RepeatedSuites.scala")
      _ = Files.deleteIfExists(flakyCounter.toNIO)
      _ = Files.deleteIfExists(failCounter.toNIO)
      _ = Files.deleteIfExists(crashCounter.toNIO)

      // A suite failing only on the first run reports the split and details
      flakyResult <- server.headServer.mcpTestRunner
        .runTestsRepeated(
          "a.FlakyOnFirstRunSuite",
          Some(path),
          None,
          times = 3,
          stopOnFirstFailure = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        flakyResult.contains(
          "Ran a.FlakyOnFirstRunSuite 3 times: 2 runs passed, 1 run failed (run 1)"
        ),
        s"Flaky result: $flakyResult",
      )
      _ = assert(
        flakyResult.contains("--- failing run 1 ---"),
        s"Should contain the failing run's report: $flakyResult",
      )
      _ = assert(
        flakyResult.contains("flaky failed"),
        s"Should contain the failed test: $flakyResult",
      )

      // A stable suite reports all runs passed and no failure section
      greenResult <- server.headServer.mcpTestRunner
        .runTestsRepeated(
          "a.GreenSuite",
          Some(path),
          None,
          times = 2,
          stopOnFirstFailure = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        greenResult.contains(
          "Ran a.GreenSuite 2 times: 2 runs passed, 0 runs failed"
        ),
        s"Green result: $greenResult",
      )
      _ = assert(
        !greenResult.contains("--- failing run"),
        s"Green result should have no failure section: $greenResult",
      )

      // stopOnFirstFailure stops after the first failing run
      stopResult <- server.headServer.mcpTestRunner
        .runTestsRepeated(
          "a.AlwaysFailingSuite",
          Some(path),
          None,
          times = 5,
          stopOnFirstFailure = true,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        stopResult.contains("0 runs passed, 1 run failed (run 1)"),
        s"Stop result: $stopResult",
      )
      _ = assert(
        stopResult.contains("Stopped after first failure (run 1 of 5)"),
        s"Stop result should mention early stop: $stopResult",
      )
      _ = assert(
        Files.size(failCounter.toNIO) == 1,
        s"Expected exactly one run, got ${Files.size(failCounter.toNIO)}",
      )

      // A run whose JVM exits non-zero counts as a failed run instead of
      // aborting the aggregate
      crashResult <- server.headServer.mcpTestRunner
        .runTestsRepeated(
          "a.CrashingSuite",
          Some(path),
          None,
          times = 2,
          stopOnFirstFailure = false,
        ) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(
        crashResult.contains("0 runs passed, 2 runs failed (runs 1, 2)"),
        s"Crash result: $crashResult",
      )
      _ = assert(
        crashResult.contains("The test run crashed"),
        s"Crash result should mention the crash: $crashResult",
      )
      _ = assert(
        Files.size(crashCounter.toNIO) == 2,
        s"Expected two runs, got ${Files.size(crashCounter.toNIO)}",
      )

      // times outside 1-100 is rejected without running anything
      _ = List(0, 101).foreach { invalidTimes =>
        server.headServer.mcpTestRunner.runTestsRepeated(
          "a.GreenSuite",
          Some(path),
          None,
          times = invalidTimes,
          stopOnFirstFailure = false,
        ) match {
          case Left(error) =>
            assert(error.contains("between 1 and 100"), error)
          case Right(_) => fail(s"Expected an error for times = $invalidTimes")
        }
      }
    } yield ()
  }
}

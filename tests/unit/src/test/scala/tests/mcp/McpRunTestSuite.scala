package tests.mcp

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
        .runTests("a.b.MunitTestSuite", Some(path), verbose = true) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res1.contains("2 tests, 1 passed, 1 failed"), res1)
      // Verbose prints all output from the test suite
      _ = assert(res1.contains("Some string"), res1)

      // Test without path and non-verbose output
      res2 <- server.headServer.mcpTestRunner
        .runTests("a.b.MunitTestSuite", None, verbose = false) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res2.contains("2 tests, 1 passed, 1 failed"), res2)
      // Non-verbose prints only errors and summary
      _ = assert(!res2.contains("Some string"), res2)
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
        .runTests("a.ZioTestSuite", Some(path), verbose = false) match {
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
    } yield ()
  }
}

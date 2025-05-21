package tests.mcp

import tests.BaseLspSuite

class McpRunTestSuite extends BaseLspSuite("mcp-test") {
  test("basic") {
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
      _ = assert(res1.contains("2 tests, 1 passed, 1 failed"))
      // Verbose prints all output from the test suite
      _ = assert(res1.contains("Some string"))

      // Test without path and non-verbose output
      res2 <- server.headServer.mcpTestRunner
        .runTests("a.b.MunitTestSuite", None, verbose = false) match {
        case Right(value) => value
        case Left(error) => throw new RuntimeException(error)
      }
      _ = assert(res2.contains("2 tests, 1 passed, 1 failed"))
      // Non-verbose prints only errors and summary
      _ = assert(!res2.contains("Some string"))
    } yield ()
  }
}

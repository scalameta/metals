package tests

class CursorMcpConfigSuite extends BaseLspSuite("mcp-test") {
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
      path = server.toPath("a/src/main/scala/a/b/c/MunitTestSuite.scala")
      res <- server.server.mcpTestRunner
        .runTests(path, "a.b.MunitTestSuite")
        .getOrElse(throw new RuntimeException("Failed to run tests"))
      _ = assert(res.contains("2 tests, 1 passed, 1 failed"))
    } yield ()
  }
}

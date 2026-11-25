package tests.mcp

import java.nio.file.Paths

import scala.meta.internal.metals.MetalsServerConfig

import tests.BaseLspSuite

class McpCompileDiagnosticsCapLspSuite
    extends BaseLspSuite("mcp-compile-diagnostics-cap")
    with McpTestUtils {

  override def serverConfig: MetalsServerConfig =
    super.serverConfig.copy(maxMcpCompileDiagnostics = 2)

  test("compile-file-diagnostics-cap") {
    cleanWorkspace()
    val path = "a/src/main/scala/com/example/Hello.scala"
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/$path
           |package com.example
           |
           |object Hello {
           |  val first: Int = "first"
           |  val second: Int = "second"
           |  val third: Int = "third"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(path)
      client <- startMcpServer()
      result <- client.compileFile(path)
      _ = assertNoDiff(
        result,
        s"""|Found errors in ${workspace.resolve(path)}:
            |L4:C20-L4:C27: [Error]
            |type mismatch;
            | found   : String("first")
            | required: Int
            |L5:C21-L5:C29: [Error]
            |type mismatch;
            | found   : String("second")
            | required: Int
            |
            |[Showing 2 of 3 errors and warnings. Fix these and compile again.]
            |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }

  test("compile-full-lists-errors-before-warnings") {
    cleanWorkspace()
    val warningsPath = "a/src/main/scala/com/example/Warnings.scala"
    val errorsPath = Paths.get("b/src/main/scala/com/example/Errors.scala")
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {"scalacOptions": ["-Wunused"]}, "b": {}}
           |/$warningsPath
           |package com.example
           |
           |object Warnings {
           |  import scala.collection.mutable.Map
           |  import scala.collection.mutable.Set
           |  import scala.collection.mutable.Buffer
           |}
           |/$errorsPath
           |package com.example
           |
           |object Errors {
           |  val first: Int = "first"
           |}
           |""".stripMargin
      )
      _ <- server.didOpen(warningsPath)
      client <- startMcpServer()
      result <- client.compileFull()
      _ = assertNoDiff(
        result,
        s"""|Compilation failed with errors:
            |$errorsPath L4:C20-L4:C27: [Error]
            |type mismatch;
            | found   : String("first")
            | required: Int
            |${Paths.get(warningsPath)} L4:C35-L4:C38: [Warning]
            |Unused import
            |
            |[Showing 2 of 4 errors and warnings. Fix these and compile again.]
            |""".stripMargin,
      )
      _ <- client.shutdown()
    } yield ()
  }
}

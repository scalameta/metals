package tests.mcp

import tests.BaseLspSuite

class McpCompileToolsLspSuite
    extends BaseLspSuite("mcp-compile-tools")
    with McpTestUtils {

  test("compile-file") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello {
           |  def main(args: Array[String]): Unit = {
           |    val x: String = 42 // Type mismatch error
           |    println(x)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      // Test compiling file with errors
      result1 <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
      _ = assert(result1.contains("Found errors in"), result1)
      _ = assert(
        result1.contains("a/src/main/scala/com/example/Hello.scala"),
        result1,
      )
      _ = assert(result1.contains("type mismatch"), result1)

      // Fix the error
      _ <- server.didChange("a/src/main/scala/com/example/Hello.scala")(
        _.replace("val x: String = 42", "val x: Int = 42")
      )
      _ <- server.didSave("a/src/main/scala/com/example/Hello.scala")

      // Test compiling fixed file
      result2 <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
      _ = assert(result2 == "Compilation successful.", result2)

      // Test compiling non-existent file
      result3 <- client.compileFile("non-existent.scala")
      _ = assert(result3.contains("Error: File not found"), result3)

      _ <- client.shutdown()
    } yield ()
  }

  test("compile-module") {
    cleanWorkspace()
    for {
      _ <- initialize(
        s"""
           |/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello {
           |  def main(args: Array[String]): Unit = {
           |    val x: String = 42 // Type mismatch error
           |    println(x)
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      // Test compiling module with errors
      result1 <- client.compileModule("a")
      _ = assert(result1.contains("Found errors in the module"), result1)
      _ = assert(result1.contains("type mismatch"), result1)

      // Fix the error
      _ <- server.didChange("a/src/main/scala/com/example/Hello.scala")(
        _.replace("val x: String = 42", "val x: Int = 42")
      )
      _ <- server.didSave("a/src/main/scala/com/example/Hello.scala")

      // Test compiling fixed module
      result2 <- client.compileModule("a")
      _ = assert(result2 == "Compilation successful.", result2)

      // Test compiling non-existent module
      result3 <- client.compileModule("non-existent")
      _ = assert(result3.contains("Error: Module not found"), result3)

      _ <- client.shutdown()
    } yield ()
  }
}

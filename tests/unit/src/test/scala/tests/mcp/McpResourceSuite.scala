package tests.mcp

import scala.concurrent.Future

import tests.BaseLspSuite

class McpResourceSuite extends BaseLspSuite("mcp-resource") with McpTestUtils {

  test("list resources") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello {
           |  def main(args: Array[String]): Unit = println("Hello")
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      resourceList <- client.listResources()
      _ = {
        val resources = ujson.read(resourceList)
        val resourceUris = resources("resources").arr.map(_("uri").str).toSet
        assert(resourceUris.contains("metals://logs/metals.log"))
        // Check for debug output resource template
        val debugOutputResource =
          resources("resources").arr.find(_("uri").str.contains("debug"))
        assert(
          debugOutputResource.isDefined,
          s"Debug output resource should be in resource list. Found URIs: ${resourceUris.mkString(", ")}",
        )
        if (debugOutputResource.isDefined) {
          assert(debugOutputResource.get("name").str == "Debug Session Output")
          assert(debugOutputResource.get("mimeType").str == "text/plain")
        }
      }
      _ <- client.shutdown()
    } yield ()
  }

  test("read metals log resource") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello {
           |  def main(args: Array[String]): Unit = {
           |    println(message) // error: not found: value message
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()
      resourceContent <- client.readResource("metals://logs/metals.log")
      _ = {
        val response = ujson.read(resourceContent)
        val contents = response("contents").arr.head
        val text = contents("text").str

        // Check that we got log content
        assert(text.nonEmpty, "Log content should not be empty")
        // The log should contain some expected patterns
        assert(
          text.contains("Starting") || text.contains("Initialized") || text
            .contains("metals") || text.contains("INFO"),
          s"Log should contain expected patterns, but got: ${text.take(200)}...",
        )
      }
      _ <- client.shutdown()
    } yield ()
  }

  test("resource content includes proper metadata") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/scala/Hello.scala
           |object Hello
           |""".stripMargin
      )
      client <- startMcpServer()

      // First check the resource list
      resourceList <- client.listResources()
      _ = {
        val resources = ujson.read(resourceList)
        val metalsLogResource = resources("resources").arr.find(
          _("uri").str == "metals://logs/metals.log"
        )
        assert(
          metalsLogResource.isDefined,
          "metals://logs/metals.log should be in resource list",
        )

        val resource = metalsLogResource.get
        assertNoDiff(resource("name").str, "Metals Server Logs")
        assertNoDiff(
          resource("description").str,
          "The main log file for the Metals language server",
        )
        assertNoDiff(resource("mimeType").str, "text/plain")
      }

      // Then check the resource contents
      resourceContent <- client.readResource("metals://logs/metals.log")
      _ = {
        val response = ujson.read(resourceContent)
        val contents = response("contents").arr.head

        assertNoDiff(contents("uri").str, "metals://logs/metals.log")
        assertNoDiff(contents("mimeType").str, "text/plain")
        assert(contents("text").str.nonEmpty)
      }

      _ <- client.shutdown()
    } yield ()
  }

  test("read debug output resource - non-existent session") {
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/Hello.scala
           |package com.example
           |
           |object Hello {
           |  def main(args: Array[String]): Unit = {
           |    println("Hello")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/Hello.scala")
      client <- startMcpServer()

      // Try to read debug output for a non-existent session
      outputNonExistent <- client.readResource(
        s"metals://debug/non-existent-session/output"
      )
      _ = {
        val response = ujson.read(outputNonExistent)
        val contents = response("contents").arr.head
        assert(
          contents("uri").str == s"metals://debug/non-existent-session/output"
        )
        assert(contents("mimeType").str == "text/plain")
        assert(contents("text").str.contains("No output found"))
      }

      // Test with query parameters for a non-existent session
      outputFiltered <- client.readResource(
        s"metals://debug/non-existent-session/output?outputType=stdout&maxLines=50"
      )
      _ = {
        val response = ujson.read(outputFiltered)
        val contents = response("contents").arr.head
        assert(
          contents(
            "uri"
          ).str == s"metals://debug/non-existent-session/output?outputType=stdout&maxLines=50"
        )
        assert(contents("text").str.contains("No output found"))
      }

      _ <- client.shutdown()
    } yield ()
  }

  test("read debug output resource - existing session with output") {
    // NOTE: This test is currently ignored because the MCP resource system
    // doesn't support dynamic URI patterns like {sessionId}. The framework
    // expects exact URI matches when looking up resources.
    cleanWorkspace()
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |/a/src/main/scala/com/example/OutputTest.scala
           |package com.example
           |
           |object OutputTest {
           |  def main(args: Array[String]): Unit = {
           |    println("Starting OutputTest program")
           |    System.err.println("This is an error message")
           |    
           |    for (i <- 1 to 5) {
           |      println(s"Iteration $i")
           |      Thread.sleep(10)
           |    }
           |    
           |    println("Program finished")
           |  }
           |}
           |""".stripMargin
      )
      _ <- server.didOpen("a/src/main/scala/com/example/OutputTest.scala")

      // Compile the workspace first
      _ = scribe.info("Compiling workspace before starting debug session")
      _ <- server.server.compilations.compileTargets(
        server.server.buildTargets.allBuildTargetIds
      )

      client <- startMcpServer()

      // Start a debug session
      debugResult <- client.debugMain("com.example.OutputTest", Some("a"))
      sessionId = {
        val result = ujson.read(debugResult)
        assert(result("status").str == "success")
        result("sessionId").str
      }
      _ = scribe.info(s"Debug session started with ID: $sessionId")

      // The program is suspended at start due to -agentlib:jdwp=...suspend=y
      // But we should still be able to read whatever output has been captured

      // Wait a bit to ensure debug session is fully established
      _ <- Future { Thread.sleep(1000) }

      // Read debug output with default parameters
      outputDefault <- client.readResource(s"metals://debug/$sessionId/output")
      _ = {
        val response = ujson.read(outputDefault)
        val contents = response("contents").arr.head
        assert(contents("uri").str == s"metals://debug/$sessionId/output")
        assert(contents("mimeType").str == "text/plain")
        // The content might be empty or contain startup messages
        val text = contents("text").str
        scribe.info(s"Default output: $text")
      }

      // Test with stdout filter
      outputStdout <- client.readResource(
        s"metals://debug/$sessionId/output?outputType=stdout"
      )
      _ = {
        val response = ujson.read(outputStdout)
        val contents = response("contents").arr.head
        assert(
          contents(
            "uri"
          ).str == s"metals://debug/$sessionId/output?outputType=stdout"
        )
        val text = contents("text").str
        scribe.info(s"Stdout output: $text")
      }

      // Test with stderr filter
      outputStderr <- client.readResource(
        s"metals://debug/$sessionId/output?outputType=stderr"
      )
      _ = {
        val response = ujson.read(outputStderr)
        val contents = response("contents").arr.head
        assert(
          contents(
            "uri"
          ).str == s"metals://debug/$sessionId/output?outputType=stderr"
        )
        val text = contents("text").str
        scribe.info(s"Stderr output: $text")
      }

      // Test with maxLines parameter
      outputLimited <- client.readResource(
        s"metals://debug/$sessionId/output?maxLines=5"
      )
      _ = {
        val response = ujson.read(outputLimited)
        val contents = response("contents").arr.head
        assert(
          contents("uri").str == s"metals://debug/$sessionId/output?maxLines=5"
        )
        val text = contents("text").str
        scribe.info(s"Limited output (5 lines): $text")
      }

      // Test with regex filter - looking for "Iteration"
      outputRegex <- client.readResource(
        s"metals://debug/$sessionId/output?regex=Iteration"
      )
      _ = {
        val response = ujson.read(outputRegex)
        val contents = response("contents").arr.head
        assert(
          contents(
            "uri"
          ).str == s"metals://debug/$sessionId/output?regex=Iteration"
        )
        val text = contents("text").str
        scribe.info(s"Regex filtered output: $text")
      }

      // Test combined parameters
      outputCombined <- client.readResource(
        s"metals://debug/$sessionId/output?outputType=stdout&maxLines=3&regex=.*"
      )
      _ = {
        val response = ujson.read(outputCombined)
        val contents = response("contents").arr.head
        assert(
          contents(
            "uri"
          ).str == s"metals://debug/$sessionId/output?outputType=stdout&maxLines=3&regex=.*"
        )
      }

      // Terminate the debug session
      _ <- client.debugTerminate(sessionId).recover { case e =>
        scribe.warn(s"Failed to terminate debug session: ${e.getMessage}")
      }

      // Test reading output after session termination (should still work)
      _ <- Future { Thread.sleep(500) } // Wait for termination

      outputAfterTermination <- client.readResource(
        s"metals://debug/$sessionId/output"
      )
      _ = {
        val response = ujson.read(outputAfterTermination)
        val contents = response("contents").arr.head
        assert(contents("uri").str == s"metals://debug/$sessionId/output")
        assert(contents("mimeType").str == "text/plain")
        // Output should be preserved after termination
        val text = contents("text").str
        scribe.info(s"Output after termination: $text")
      }

      _ <- client.shutdown()
    } yield ()
  }
}

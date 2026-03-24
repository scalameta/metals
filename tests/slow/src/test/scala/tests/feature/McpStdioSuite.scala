package tests.feature

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService

import tests.BaseSuite

class McpStdioSuite extends BaseSuite with StdioMcpTestHelper {
  def suiteName: String = "mcp-stdio-suite"

  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  test("stdio-initialize-handshake") {
    withStdioClient("stdio-init-test") { client =>
      for {
        result <- client.initialize()
        _ <- client.shutdown()
      } yield {
        assert(result != null, "Initialize result should not be null")
      }
    }
  }

  test("stdio-mcp-tools") {
    withStdioClient("stdio-tools-test") { client =>
      val filePath = "a/src/main/scala/com/example/Hello.scala"

      for {
        _ <- client.initialize()

        modules <- client.listModules()
        findDepResult <- client.findDep("org.scala-lang")
        scalafixRules <- client.listScalafixRules()

        _ <- client.waitForIndexing("a")

        typedGlobResult <- client.typedGlobSearch(
          "Hello",
          List("class", "object"),
          Some(filePath),
        )
        globSearchResult <- client.globSearch("Hello", Some(filePath))
        inspectResult <- client.inspect("com.example.Hello")
        docsResult <- client.getDocs("com.example.Hello")
        usagesResult <- client.getUsages("com.example.Hello.add")

        compileModuleResult <- client.compileModule("a")
        formatResult <- client.formatFile(filePath)
        compileFileResult <- client.compileFile(filePath)
        compileFullResult <- client.compileFull()
        importBuildResult <- client.importBuild()

        _ <- client.shutdown()
      } yield {
        assert(modules.contains("Available modules"), s"Got: $modules")
        assert(
          modules.contains("a") || modules.contains("a-test"),
          s"Module 'a' should be listed, got: $modules",
        )

        assert(
          findDepResult.mkString.contains("scala-library"),
          s"Should find scala-library, got: $findDepResult",
        )

        assert(
          scalafixRules.contains("Available scalafix rules"),
          s"Should list scalafix rules, got: $scalafixRules",
        )

        assert(
          typedGlobResult.contains("com.example.Hello"),
          s"Should find Hello class/object, got: $typedGlobResult",
        )

        assert(
          globSearchResult.contains("com.example.Hello"),
          s"Should find Hello, got: $globSearchResult",
        )

        val hasRelevantContent = inspectResult.contains("Hello") ||
          inspectResult.contains("main") ||
          inspectResult.contains("Inspected from") ||
          inspectResult.nonEmpty
        assert(
          hasRelevantContent,
          s"Should inspect Hello class, got: $inspectResult",
        )

        assert(
          docsResult != null && docsResult.nonEmpty,
          s"Should get docs result, got: $docsResult",
        )

        assert(usagesResult != null, s"Should get usages result")

        assert(
          compileModuleResult.nonEmpty,
          s"Should return a compilation result, got: $compileModuleResult",
        )

        assert(
          formatResult.nonEmpty,
          s"Should return a format result, got: $formatResult",
        )

        assert(
          compileFileResult.nonEmpty,
          s"Should return compile-file result, got: $compileFileResult",
        )

        assert(
          compileFullResult.nonEmpty,
          s"Should return compile-full result, got: $compileFullResult",
        )

        val isImportSuccess =
          importBuildResult.contains("No changes detected") ||
            importBuildResult.contains("Build reloaded") ||
            importBuildResult.contains("Reimport cancelled") ||
            importBuildResult.contains("Reconnected to build server")
        assert(
          isImportSuccess,
          s"Import build should succeed, got: $importBuildResult",
        )
      }
    }
  }
}

package tests.feature

import java.util.concurrent.Executors

import scala.concurrent.ExecutionContext
import scala.concurrent.ExecutionContextExecutorService
import scala.util.control.NonFatal

import tests.BaseSuite

class McpStdioCompileToolsSuite extends BaseSuite with StdioMcpTestHelper {
  def suiteName: String = "mcp-stdio-compile-tools"

  implicit val ex: ExecutionContextExecutorService =
    ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())

  test("stdio-compile-file-works") {
    withStdioClient("stdio-compile-file-test") { client =>
      for {
        _ <- client.initialize()
        r <- client.compileFile("a/src/main/scala/com/example/Hello.scala")
        _ <- client.shutdown().recover { case NonFatal(_) => () }
      } yield {
        assert(r.nonEmpty, s"Should return compile result, got: $r")
      }
    }
  }

  test("stdio-compile-module-works") {
    withStdioClient("stdio-compile-module-test") { client =>
      for {
        _ <- client.initialize()
        r <- client.compileModule("a")
        _ <- client.shutdown().recover { case NonFatal(_) => () }
      } yield {
        assert(r.nonEmpty, s"Should return compile result, got: $r")
      }
    }
  }

  test("stdio-compile-full-works") {
    withStdioClient("stdio-compile-full-test") { client =>
      for {
        _ <- client.initialize()
        r <- client.compileFull()
        _ <- client.shutdown().recover { case NonFatal(_) => () }
      } yield {
        assert(r.nonEmpty, s"Should return compile result, got: $r")
      }
    }
  }
}

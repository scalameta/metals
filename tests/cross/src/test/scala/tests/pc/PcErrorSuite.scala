package tests.pc

import java.net.URI

import scala.compat.java8.FutureConverters
import scala.concurrent.ExecutionContext
import scala.concurrent.duration.Duration

import scala.meta.pc.CancelToken
import scala.meta.pc.VirtualFileParams

import tests.BasePCSuite

class PcErrorSuite extends BasePCSuite {
  object TestError extends Throwable("test")
  class ThrowingVirtualFileParams extends VirtualFileParams {
    override def uri(): URI = throw TestError
    override def text(): String = throw TestError
    override def token(): CancelToken = throw TestError
  }

  override def munitTimeout: Duration = Duration(5, "s")
  implicit val ec: ExecutionContext = munitExecutionContext

  // Assert that we get a failing future from the PC.
  test("error") {
    for {
      error <- FutureConverters
        .toScala(
          presentationCompiler.semanticdbTextDocument(
            new ThrowingVirtualFileParams()
          )
        )
        .failed
    } yield {
      assertEquals(error, TestError)
    }
  }

}

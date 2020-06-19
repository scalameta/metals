package tests

import java.util.concurrent.TimeUnit

import scala.concurrent.TimeoutException
import scala.util.Failure
import scala.util.Success

import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.InitializationOptions
import scala.meta.internal.metals.MetalsEnrichments._

class UnsupportedDebuggingLspSuite
    extends BaseLspSuite("unsupported-debugging") {

  override val initializationOptions: Some[InitializationOptions] =
    Some(
      // NOTE: Default is fine here since they default to off
      InitializationOptions.Default
    )

  test("no-code-lenses") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{ "a": { } }
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = ???
           |}
           |""".stripMargin
      )
      codeLenses <-
        server
          .codeLenses("a/src/main/scala/Main.scala")(maxRetries = 3)
          .withTimeout(5, TimeUnit.SECONDS)
          .transform(Success(_))
    } yield {
      codeLenses match {
        case Failure(_: TimeoutException) =>
        // success
        case result =>
          fail(
            s"Expected timeout when retrieving code lenses. Obtained [$result]"
          )
      }
    }
  }

  test("suppress-model-refresh") {
    for {
      _ <- server.initialize(
        """|/metals.json
           |{ "a": { } }
           |
           |/a/src/main/scala/Main.scala
           |object Main {
           |  def main(args: Array[String]): Unit = ???
           |}
           |""".stripMargin
      )
      _ <-
        server.server.compilations
          .compileFile(server.toPath("a/src/main/scala/Main.scala"))
    } yield {
      val clientCommands = client.clientCommands.asScala.map(_.getCommand).toSet
      assert(!clientCommands.contains(ClientCommands.RefreshModel.id))
    }
  }
}

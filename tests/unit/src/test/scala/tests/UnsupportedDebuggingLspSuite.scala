package tests

import scala.meta.internal.metals.ClientCommands
import scala.meta.internal.metals.ClientExperimentalCapabilities
import scala.meta.internal.metals.MetalsEnrichments._

object UnsupportedDebuggingLspSuite
    extends BaseLspSuite("unsupported-debugging") {

  override val experimentalCapabilities: Some[ClientExperimentalCapabilities] =
    Some(
      new ClientExperimentalCapabilities(
        debuggingProvider = false,
        treeViewProvider = false
      )
    )

  testAsync("no-code-lenses") {
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
      withLenses <- server.codeLenses("a/src/main/scala/Main.scala")
    } yield {
      val originalContent = server.textContents("a/src/main/scala/Main.scala")
      assertNoDiff(withLenses, originalContent)
    }
  }

  testAsync("suppress-model-refresh") {
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
      _ <- server.server.compilations
        .compileFiles(List(server.toPath("a/src/main/scala/Main.scala")))
    } yield {
      val clientCommands = client.clientCommands.asScala.map(_.getCommand).toSet
      assert(!clientCommands.contains(ClientCommands.RefreshModel.id))
    }
  }
}

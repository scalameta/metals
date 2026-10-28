package tests

import java.util.concurrent.TimeUnit

import scala.meta.internal.metals.MetalsEnrichments._

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import org.eclipse.lsp4j.InlayHintParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range

class UserConfigurationSyncLspSuite
    extends BaseLspSuite("user-configuration-sync") {

  test("null-client-settings") {
    client.supportsConfiguration = true
    client.configurationResponse = { params =>
      params.getItems.asScala.map(_ => JsonNull.INSTANCE: Object).asJava
    }
    val file = "a/src/main/scala/a/Main.scala"
    val text = "object Main\n"
    for {
      _ <- initialize(
        s"""|/metals.json
            |{"a": {}}
            |/$file
            |$text
            |""".stripMargin
      )
      _ <- server.didOpen(file)
      params = new InlayHintParams(
        workspace.resolve(file).toTextDocumentIdentifier,
        new Range(new Position(0, 0), new Position(1, 0)),
      )
      // Semantic tokens and inlay hints wait until user configuration is set.
      // A null settings section used to leave that unfinished.
      _ <- server.fullServer
        .inlayHints(params)
        .asScala
        .withTimeout(20, TimeUnit.SECONDS, Some("null client configuration"))
    } yield {
      assert(server.server.userConfig.fallbackScalaVersion.isEmpty)
    }
  }

  test("client-settings-object") {
    client.supportsConfiguration = true
    client.configurationResponse = { params =>
      params.getItems.asScala.map { _ =>
        val settings = new JsonObject()
        settings.addProperty("java-home", "/tmp/custom-java")
        settings: Object
      }.asJava
    }
    for {
      _ <- initialize(
        """|/metals.json
           |{"a": {}}
           |""".stripMargin
      )
    } yield {
      assertEquals(
        server.server.userConfig.javaHome,
        Some("/tmp/custom-java"),
      )
    }
  }
}

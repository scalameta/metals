package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

final case class ClientExperimentalCapabilities(
    debuggingProvider: java.lang.Boolean,
    treeViewProvider: java.lang.Boolean
)

object ClientExperimentalCapabilities {
  val Default = new ClientExperimentalCapabilities(
    debuggingProvider = false,
    treeViewProvider = false
  )

  def from(
      capabilities: l.ClientCapabilities
  ): ClientExperimentalCapabilities = {
    import scala.meta.internal.metals.JsonParser._
    capabilities.getExperimental match {
      case json: JsonElement =>
        json.as[ClientExperimentalCapabilities].getOrElse(Default)
      case _ =>
        Default
    }
  }
}

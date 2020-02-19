package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

final case class ClientExperimentalCapabilities(
    debuggingProvider: java.lang.Boolean = false,
    treeViewProvider: java.lang.Boolean = false,
    decorationProvider: java.lang.Boolean = false,
    inputBoxProvider: java.lang.Boolean = false,
    didFocusProvider: java.lang.Boolean = false,
    slowTaskProvider: java.lang.Boolean = false,
    executeClientCommandProvider: java.lang.Boolean = false,
    doctorProvider: String = "html",
    statusBarProvider: String = "off"
) {
  def doctorFormatIsJson: Boolean = doctorProvider == "json"
  def statusBarIsOn: Boolean = statusBarProvider == "on"
  def statusBarIsOff: Boolean = statusBarProvider == "off"
  def statusBarIsShowMessage: Boolean = statusBarProvider == "show-message"
  def statusBarIsLogMessage: Boolean = statusBarProvider == "log-message"
}

object ClientExperimentalCapabilities {
  val Default = new ClientExperimentalCapabilities()

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

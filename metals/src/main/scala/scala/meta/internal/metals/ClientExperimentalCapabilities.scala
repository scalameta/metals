package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

final case class ClientExperimentalCapabilities(
    debuggingProvider: Boolean,
    decorationProvider: Boolean,
    didFocusProvider: Boolean,
    doctorProvider: String,
    executeClientCommandProvider: Boolean,
    inputBoxProvider: Boolean,
    openFilesOnRenameProvider: Boolean,
    quickPickProvider: Boolean,
    slowTaskProvider: Boolean,
    statusBarProvider: String,
    treeViewProvider: Boolean
) {
  def this() =
    this(
      false, false, false, "html", false, false, false, false, false, "off",
      false
    )
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

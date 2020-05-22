package scala.meta.internal.metals
import com.google.gson.JsonElement
import com.google.gson.JsonNull
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
      debuggingProvider = false,
      decorationProvider = false,
      didFocusProvider = false,
      doctorProvider = "html",
      executeClientCommandProvider = false,
      inputBoxProvider = false,
      openFilesOnRenameProvider = false,
      quickPickProvider = false,
      slowTaskProvider = false,
      statusBarProvider = "off",
      treeViewProvider = false
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
      // NOTE: (ckipp01) For some reason when some editors (emacs) leave out the
      // `InitializationOptions` key, it is parsed as a `JsonNull` while others
      // that leave it out it's parsed as a normal `null`. Why? I have no idea.
      case _: JsonNull => Default
      case json: JsonElement =>
        json.as[ClientExperimentalCapabilities].getOrElse(Default)
      case _ =>
        Default
    }
  }
}

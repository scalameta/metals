package scala.meta.internal.metals
import com.google.gson.JsonObject
import org.eclipse.{lsp4j => l}

/**
 * While all these values can be set here, they are here only for
 * compatablity with clients that were setting them this way. From
 * a client perspective it's preferable and recommended to use
 * InitializationOptions instead. From a development perspective
 * don't add something here unless it's truly a more "experimental"
 * type feature.
 */
final case class ClientExperimentalCapabilities(
    debuggingProvider: Option[Boolean],
    decorationProvider: Option[Boolean],
    didFocusProvider: Option[Boolean],
    doctorProvider: Option[String],
    executeClientCommandProvider: Option[Boolean],
    inputBoxProvider: Option[Boolean],
    openFilesOnRenameProvider: Option[Boolean],
    quickPickProvider: Option[Boolean],
    slowTaskProvider: Option[Boolean],
    statusBarProvider: Option[String],
    treeViewProvider: Option[Boolean]
) {
  def doctorFormatIsJson: Boolean = doctorProvider.exists(_ == "json")
  def statusBarIsOn: Boolean = statusBarProvider.exists(_ == "on")
  def statusBarIsOff: Boolean = statusBarProvider.exists(_ == "off")
  def statusBarIsShowMessage: Boolean =
    statusBarProvider.exists(_ == "show-message")
  def statusBarIsLogMessage: Boolean =
    statusBarProvider.exists(_ == "log-message")
}

object ClientExperimentalCapabilities {
  import scala.meta.internal.metals.JsonParser._
  val Default: ClientExperimentalCapabilities = ClientExperimentalCapabilities(
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None
  )

  def from(
      capabilities: l.ClientCapabilities
  ): ClientExperimentalCapabilities = {
    capabilities.getExperimental match {
      case json: JsonObject =>
        // Note (ckipp01) We used to do this with reflection going from the JsonElement straight to
        // InitializationOptions, but there was a lot of issues with it such as setting
        // nulls for unset values, needing a default constructor, and not being able to
        // work as expected with Options. This is a bit more verbose, but it gives us full
        // control over how the InitializationOptions are created.
        extractToClientExperimentalCapabilities(json)
      case _ =>
        Default
    }
  }

  def extractToClientExperimentalCapabilities(
      json: JsonObject
  ): ClientExperimentalCapabilities = {
    val jsonObj = json.toJsonObject
    val debuggingProvider = jsonObj.getBooleanOption("debuggingProvider")
    val decorationProvider =
      jsonObj.getBooleanOption("decorationProvider")
    val didFocusProvider = jsonObj.getBooleanOption("didFocusProvider")
    val doctorProvider = jsonObj.getStringOption("doctorProvider")
    val executeClientCommandProvider =
      jsonObj.getBooleanOption("executeClientCommandProvider")
    val inputBoxProvider = jsonObj.getBooleanOption("inputBoxProvider")
    val openFilesOnRenameProvider =
      jsonObj.getBooleanOption("openFilesOnRenameProvider")
    val quickPickProvider = jsonObj.getBooleanOption("quickPickProvider")
    val slowTaskProvider = jsonObj.getBooleanOption("slowTaskProvider")
    val statusBarProvider = jsonObj.getStringOption("statusBarProvider")
    val treeViewProvider = jsonObj.getBooleanOption("treeViewProvider")
    ClientExperimentalCapabilities(
      debuggingProvider,
      decorationProvider,
      didFocusProvider,
      doctorProvider,
      executeClientCommandProvider,
      inputBoxProvider,
      openFilesOnRenameProvider,
      quickPickProvider,
      slowTaskProvider,
      statusBarProvider,
      treeViewProvider
    )
  }
}

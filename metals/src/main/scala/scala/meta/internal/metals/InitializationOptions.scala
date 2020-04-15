package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}

final case class InitializationOptions(
    statusBar: String = "off",
    didFocus: java.lang.Boolean = false,
    slowTask: java.lang.Boolean = false,
    inputBox: java.lang.Boolean = false,
    quickPick: java.lang.Boolean = false,
    executeClientCommand: java.lang.Boolean = false,
    doctorFormat: String = "html",
    isCompletionItemDetailEnabled: java.lang.Boolean = false,
    isExitOnShutdown: java.lang.Boolean = false
) {
  def doctorFormatIsJson: Boolean = doctorFormat == "json"
  def statusBarIsOn: Boolean = statusBar == "on"
  def statusBarIsOff: Boolean = statusBar == "off"
  def statusBarIsShowMessage: Boolean = statusBar == "show-message"
  def statusBarIsLogMessage: Boolean = statusBar == "log-message"
}

object InitializationOptions {
  val Default = new InitializationOptions()

  def from(
      initializeParams: l.InitializeParams
  ): InitializationOptions = {
    import scala.meta.internal.metals.JsonParser._
    initializeParams.getInitializationOptions() match {
      case json: JsonElement =>
        json.as[InitializationOptions].getOrElse(Default)
      case _ =>
        Default
    }
  }
}

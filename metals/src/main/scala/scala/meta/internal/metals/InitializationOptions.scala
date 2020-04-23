package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}
import scala.meta.internal.pc.CompilerInitializationOptions

final case class InitializationOptions(
    statusBarProvider: String = "off",
    didFocusProvider: java.lang.Boolean = false,
    slowTaskProvider: java.lang.Boolean = false,
    inputBoxProvider: java.lang.Boolean = false,
    quickPickProvider: java.lang.Boolean = false,
    executeClientCommandProvider: java.lang.Boolean = false,
    doctorProvider: String = "html",
    isExitOnShutdown: java.lang.Boolean = false,
    isHttpEnabled: java.lang.Boolean = false,
    compilerOptions: CompilerInitializationOptions =
      CompilerInitializationOptions()
) {
  def doctorFormatIsJson: Boolean = doctorProvider == "json"
  def statusBarIsOn: Boolean = statusBarProvider == "on"
  def statusBarIsOff: Boolean = statusBarProvider == "off"
  def statusBarIsShowMessage: Boolean = statusBarProvider == "show-message"
  def statusBarIsLogMessage: Boolean = statusBarProvider == "log-message"
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

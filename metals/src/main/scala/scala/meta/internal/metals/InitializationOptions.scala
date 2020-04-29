package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}
import scala.meta.internal.pc.CompilerInitializationOptions

final case class InitializationOptions(
    compilerOptions: CompilerInitializationOptions,
    didFocusProvider: Boolean,
    doctorProvider: String,
    executeClientCommandProvider: Boolean,
    inputBoxProvider: Boolean,
    isExitOnShutdown: Boolean,
    isHttpEnabled: Boolean,
    quickPickProvider: Boolean,
    slowTaskProvider: Boolean,
    statusBarProvider: String
) {
  def this() =
    this(
      CompilerInitializationOptions(),
      false,
      "html",
      false,
      false,
      false,
      false,
      false,
      false,
      "off"
    )
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

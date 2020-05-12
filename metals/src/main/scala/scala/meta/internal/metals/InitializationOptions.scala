package scala.meta.internal.metals
import com.google.gson.JsonElement
import org.eclipse.{lsp4j => l}
import scala.meta.internal.pc.CompilerInitializationOptions
import com.google.gson.JsonNull

final case class InitializationOptions(
    compilerOptions: CompilerInitializationOptions,
    debuggingProvider: Boolean,
    decorationProvider: Boolean,
    didFocusProvider: Boolean,
    doctorProvider: String,
    executeClientCommandProvider: Boolean,
    inputBoxProvider: Boolean,
    isExitOnShutdown: Boolean,
    isHttpEnabled: Boolean,
    openFilesOnRenameProvider: Boolean,
    quickPickProvider: Boolean,
    slowTaskProvider: Boolean,
    statusBarProvider: String,
    treeViewProvider: Boolean
) {
  def this() =
    this(
      compilerOptions = new CompilerInitializationOptions(),
      debuggingProvider = false,
      decorationProvider = false,
      didFocusProvider = false,
      doctorProvider = "html",
      executeClientCommandProvider = false,
      inputBoxProvider = false,
      isExitOnShutdown = false,
      isHttpEnabled = false,
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

object InitializationOptions {
  val Default = new InitializationOptions()

  def from(
      initializeParams: l.InitializeParams
  ): InitializationOptions = {
    import scala.meta.internal.metals.JsonParser._
    initializeParams.getInitializationOptions() match {
      // NOTE: (ckipp01) For some reason when some editors (emacs) leave out the
      // `InitializationOptions` key, it is parsed as a `JsonNull` while others
      // that leave it out it's parsed as a normal `null`. Why? I have no idea.
      case _: JsonNull =>
        Default
      case json: JsonElement =>
        json.as[InitializationOptions].getOrElse(Default)
      case _ =>
        Default
    }
  }
}

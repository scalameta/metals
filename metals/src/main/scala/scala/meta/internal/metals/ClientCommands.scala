package scala.meta.internal.metals

/**
 * Optional commands that metals expects the client to implement.
 */
object ClientCommands {

  /**
   * Focus or remove focus on the output logs reported by the server via `window/logMessage`.
   *
   * In VS Code, this opens the "output" channel for the Metals extension.
   */
  val ToggleLogs = "metals.logs.toggle"

  /**
   * Focus on the window that lists all published diagnostics.
   *
   * In VS Code, this opens the "problems" window.
   */
  val FocusDiagnostics = "metals.diagnostics.focus"

}

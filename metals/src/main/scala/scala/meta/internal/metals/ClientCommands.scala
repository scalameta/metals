package scala.meta.internal.metals

/**
 * Optional commands that metals expects the client to implement.
 */
object ClientCommands {

  val RunDoctor = Command(
    "metals-doctor-run",
    "Run doctor",
    """Focus on a window displaying troubleshooting help from the Metals doctor.""".stripMargin,
    arguments =
      """`string`, the HTML to display in the focused window.""".stripMargin
  )

  val ReloadDoctor = Command(
    "metals-doctor-reload",
    "Reload doctor",
    """Reload the HTML contents of an open Doctor window, if any. Should be ignored if there is no open doctor window.""".stripMargin,
    arguments =
      """`string`, the HTML to display in the focused window.""".stripMargin
  )

  val ToggleLogs = Command(
    "metals-logs-toggle",
    "Toggle logs",
    """|Focus or remove focus on the output logs reported by the server via `window/logMessage`.
       |
       |In VS Code, this opens the "output" channel for the Metals extension.
       |""".stripMargin
  )

  val FocusDiagnostics = Command(
    "metals-diagnostics-focus",
    "Open problems",
    """|Focus on the window that lists all published diagnostics.
       |
       |In VS Code, this opens the "problems" window.
       |""".stripMargin
  )

  def all: List[Command] = List(
    RunDoctor,
    ToggleLogs,
    FocusDiagnostics
  )
}

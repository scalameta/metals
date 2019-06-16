package scala.meta.internal.metals

/**
 * Optional commands that metals expects the client to implement.
 */
object ClientCommands {

  val EchoCommand = Command(
    "metals-echo-command",
    "Echo command",
    """A client command that should be forwarded back to the Metals server.
      |
      |Metals may register commands in client UIs like tree view nodes that should be
      |forwarded back to the Metals server if the client clicks on the UI elements.
      |""".stripMargin,
    arguments =
      """`string`, the command ID to execute on the client.""".stripMargin
  )

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

  val RunMain = Command(
    "metals-main-run",
    "run",
    "Runs main method",
    """|Array of strings of length 2 where
       |- first array element is a build target identifier URI
       |- second array element is the name of the class containing the main method 
       |Example: `["mybuild://workspace/foo/?id=foo", "com.app.Main"]`
    """.stripMargin
  )

  val GotoLocation = Command(
    "metals-goto-location",
    "Goto location",
    "Move the cursor focus the the provided location",
    """|A LSP `Location` object with `uri` and `range` fields.
       |Example: 
       |```json
       |{
       |  "uri": "file://path/to/Definition.scala",
       |  "range": {
       |    "start": {"line": 194, "character": 0},
       |    "end":   {"line": 194, "character": 1}
       |  }
       |}
       |```
       |""".stripMargin
  )

  val TreeViewRevealNode = Command(
    "metals-reveal-treeview",
    "Reveal tree view node",
    "Reveal the tree view node with the given URI.",
    """|An object with string fields `viewId` and `uri`.
       |Example: 
       |```json
       |{
       |  "viewId": "build",
       |  "uri": "libraries:path/to/scala-library.jar!/scala/Predef."
       |}
       |```
       |""".stripMargin
  )

  def all: List[Command] = List(
    EchoCommand,
    GotoLocation,
    RunDoctor,
    ToggleLogs,
    FocusDiagnostics,
    GotoLocation,
    TreeViewRevealNode
  )
}

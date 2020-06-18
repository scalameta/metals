package scala.meta.internal.metals

import ch.epfl.scala.{bsp4j => b}

/**
 * Optional commands that metals expects the client to implement.
 */
object ClientCommands {

  val EchoCommand = new Command(
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

  val RunDoctor = new Command(
    "metals-doctor-run",
    "Run doctor",
    """Focus on a window displaying troubleshooting help from the Metals doctor.""".stripMargin,
    arguments =
      """`string`, the HTML to display in the focused window.""".stripMargin
  )

  val ReloadDoctor = new Command(
    "metals-doctor-reload",
    "Reload doctor",
    """Reload the HTML contents of an open Doctor window, if any. Should be ignored if there is no open doctor window.""".stripMargin,
    arguments =
      """`string`, the HTML to display in the focused window.""".stripMargin
  )

  val ToggleLogs = new Command(
    "metals-logs-toggle",
    "Toggle logs",
    """|Focus or remove focus on the output logs reported by the server via `window/logMessage`.
       |
       |In VS Code, this opens the "output" channel for the Metals extension.
       |""".stripMargin
  )

  val FocusDiagnostics = new Command(
    "metals-diagnostics-focus",
    "Open problems",
    """|Focus on the window that lists all published diagnostics.
       |
       |In VS Code, this opens the "problems" window.
       |""".stripMargin
  )

  val StartRunSession = new Command(
    "metals-run-session-start",
    "Start run session",
    s"""|Starts a run session. The address of a new Debug Adapter can be obtained 
        | by using the ${ServerCommands.StartDebugAdapter.id} metals server command
        | with the same arguments as provided to this command.
    """.stripMargin,
    s"""|DebugSessionParameters object. It should be forwarded
        |to the ${ServerCommands.StartDebugAdapter.id} command as is.
        |
        |Example:
        |```json
        |{
        |  "targets": ["mybuild://workspace/foo/?id=foo"],
        |   dataKind: "${b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS}",
        |   data: {
        |      className: "com.foo.App"
        |   }
        |}```
    """.stripMargin
  )

  val StartDebugSession = new Command(
    "metals-debug-session-start",
    "Start debug session",
    s"""|Starts a debug session. The address of a new Debug Adapter can be obtained 
        | by using the ${ServerCommands.StartDebugAdapter.id} metals server command
        | with the same arguments as provided to this command.
    """.stripMargin,
    s"""|DebugSessionParameters object. It should be forwarded
        |to the ${ServerCommands.StartDebugAdapter.id} command as is.
        |
        |Example:
        |```json
        |{
        |  "targets": ["mybuild://workspace/foo/?id=foo"],
        |   dataKind: "${b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS}",
        |   data: {
        |      className: "com.foo.App"
        |   }
        |}```
    """.stripMargin
  )

  val RefreshModel = new Command(
    "metals-model-refresh",
    "Refresh model",
    """|Notifies the client that the model has been updated and it 
       |should be refreshed (e.g. by resending code lens request)
       |""".stripMargin
  )

  val GotoLocation = new Command(
    "metals-goto-location",
    "Goto location",
    "Move the cursor focus to the provided location",
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

  val OpenFolder = new Command(
    "metals-open-folder",
    "Open a specified folder either in the same or new window",
    """Open a new window with the specified directory.""".stripMargin,
    """|An object with `uri` and `newWindow` fields.
       |Example: 
       |```json
       |{
       |  "uri": "file://path/to/directory",
       |  "newWindow": true
       |}
       |```
       |""".stripMargin
  )

  def all: List[Command] =
    List(
      OpenFolder,
      RunDoctor,
      ReloadDoctor,
      ToggleLogs,
      FocusDiagnostics,
      GotoLocation,
      EchoCommand
    )
}

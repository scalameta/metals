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
    """|Focus on a window displaying troubleshooting help from the Metals doctor.
       |
       |If `doctorProvider` is set to `"json"` then the schema is as follows:
       |```json
       |export interface DoctorOutput {
       |  /** Metals Doctor title */
       |  title: string;
       |  /**
       |   * Contains decisions that were made about what build tool or build server
       |   * the user has chosen. There is also other brief information about understanding
       |   * the Doctor placed in here as well.
       |   */
       |   headerText: string;
       |   /**
       |    * If build targets are detected in your workspace, they will be listed here with
       |    * the status of related functionality of Metals for each build target.
       |    */
       |   targets?: DoctorBuildTarget[];
       |   /** Messages given if build targets cannot be found */
       |   messages?: DoctorRecommendation[];
       |}
       |
       |```
       |```json
       |export interface DoctorBuildTarget {
       |  /** Name of the build target */
       |  buildTarget: string;
       |  /** Scala version of the build target */
       |  scalaVersion: string;
       |  /** Status of diagnostics */
       |  diagnostics: string;
       |  /** Status of goto definitions */
       |  gotoDefinition: string;
       |  /** Status of completions */
       |  completions: string;
       |  /** Status of find references */
       |  findReferences: string;
       |  /** Any recommendations in how to fix any issues that are found above */
       |  recommendation: string;
       |}
       |```
       |```json
       |export interface DoctorRecommendation {
       |  /** Title of the recommendation */
       |  title: string;
       |  /** Recommendations related to the found issue. */
       |  recommendations: string[]
       |}
       |```
       |""".stripMargin,
    arguments =
      """`string`, the HTML to display in the focused window.""".stripMargin
  )

  val ReloadDoctor = new Command(
    "metals-doctor-reload",
    "Reload doctor",
    """|Reload the HTML contents of an open Doctor window, if any. Should be ignored if there is no open doctor window.
       |If `doctorProvider` is set to `"json"`, then the schema is the same as found above in `"metals-run-doctor"`""".stripMargin,
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
    """|First required parameter is LSP `Location` object with `uri` and `range` fields.
       |Second parameter is optional and has signature `otherWindow: Boolean`. 
       |It gives a hint to client that if possible it would be good to open location in
       |another buffer/window.
       |Example: 
       |```json
       |[{
       |  "uri": "file://path/to/Definition.scala",
       |  "range": {
       |    "start": {"line": 194, "character": 0},
       |    "end":   {"line": 194, "character": 1}
       |  }
       |},
       |  false
       |]
       |```
       |""".stripMargin
  )

  val SaveAndRename = new Command(
    "metals-save-and-rename",
    "Save file and run rename on a location",
    "Move the cursor focus to the provided location",
    """|First required parameter is LSP `Location` object with `uri` and `range` fields.
       |Second parameter is optional and has signature `otherWindow: Boolean`. 
       |It gives a hint to client that if possible it would be good to open location in
       |another buffer/window.
       |Example: 
       |```json
       |[{
       |  "uri": "file://path/to/Definition.scala",
       |  "range": {
       |    "start": {"line": 194, "character": 0},
       |    "end":   {"line": 194, "character": 1}
       |  }
       |},
       |  false
       |]
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

  val CopyWorksheetOutput = new Command(
    "metals.copy-worksheet-output",
    "Copy Worksheet Output",
    s"""|Copy the contents of a worksheet to your local buffer.
        |
        |Note: This command should execute the ${ServerCommands.CopyWorksheetOutput.id} 
        |      server command to get the output to copy into the buffer.
        |
        |Server will attempt to create code lens with this command if `copyWorksheetOutputProvider` option is set.
        |""".stripMargin,
    "[uri], the uri of the worksheet that you'd like to copy the contents of."
  )

  def all: List[Command] =
    List(
      OpenFolder,
      RunDoctor,
      ReloadDoctor,
      ToggleLogs,
      FocusDiagnostics,
      GotoLocation,
      EchoCommand,
      RefreshModel
    )
}

package scala.meta.internal.metals

import javax.annotation.Nullable

import scala.util.matching.Regex

import ch.epfl.scala.{bsp4j => b}

/**
 * LSP commands supported by the Metals language server.
 */
object ServerCommands {

  val ImportBuild = new Command(
    "build-import",
    "Import build",
    """Import the latest changes from the build to for example pick up new library dependencies.
      |
      |By default, Metals automatically prompts you to import the build when sources of the build change.
      |Use this command to manually trigger an import build instead of relying on the automatic prompt.
      |""".stripMargin
  )

  val ConnectBuildServer = new Command(
    "build-connect",
    "Connect to build server",
    """Establish a new connection to the build server and reindex the workspace.
      |
      |This command can be helpful in scenarios when Metals feels unresponsive, for example
      |when reopening Metals after the computer it has been sleeping.
      |""".stripMargin
  )

  val DisconnectBuildServer = new Command(
    "build-disconnect",
    "Disconnect to build server",
    """Unconditionally cancel existing build server connection without reconnecting"""
  )

  val RestartBuildServer = new Command(
    "build-restart",
    "Restart build server",
    """Unconditionally stop the current running Bloop server and start a new one using Bloop launcher"""
  )

  val ScanWorkspaceSources = new Command(
    "sources-scan",
    "Scan sources",
    """|Walk all files in the workspace and index where symbols are defined.
       |
       |Is automatically run once after `initialized` notification and incrementally
       |updated on file watching events. A language client that doesn't support
       |file watching can run this manually instead. It should not be much slower
       |than walking the entire file tree and reading `*.scala` files to string,
       |indexing itself is cheap.
       |""".stripMargin
  )

  val RunDoctor = new Command(
    "doctor-run",
    "Run doctor",
    """|Open the Metals doctor to troubleshoot potential problems with the build.
       |
       |This command can be helpful in scenarios where features are not working as expected such
       |as compile errors are not appearing or completions are not correct.
       |""".stripMargin
  )

  val CascadeCompile = new Command(
    "compile-cascade",
    "Cascade compile",
    """|Compile the current open files along with all build targets in this workspace that depend on those files.
       |
       |By default, Metals compiles only the current build target and its dependencies when saving a file.
       |Run the cascade compile task to additionally compile the inverse dependencies of the current build target.
       |For example, if you change the API in main sources and run cascade compile then it will also compile the
       |test sources that depend on main.
       |""".stripMargin
  )

  val CleanCompile = new Command(
    "compile-clean",
    "Clean compile workspace",
    """|Recompile all build targets in this workspace.
       |
       |By default, Metals compiles the files incrementally. In case of any compile artifacts corruption 
       |this command might be run to make sure everything is recompiled correctly.
       |""".stripMargin
  )

  val CancelCompile = new Command(
    "compile-cancel",
    "Cancel compilation",
    """Cancel the currently ongoing compilation, if any."""
  )

  val GenerateBspConfig = new Command(
    "generate-bsp-config",
    "Generate BSP Config",
    """|Checks to see if your build tool can serve as a BSP server. If so, generate
       |the necessary BSP config to connect to the server. If there is more than one
       |build tool for a workspace, you can then choose the desired one and that
       |one will be used to generate the config.
       |
       |After the config is generated, Metals will attempt to auto-connect to it.
       |
       |The build servers that Metals knows how to detect and start:
       | - sbt
       |
       |Note: while Metals does know how to start Bloop, Bloop will be started when you trigger a build
       |import or when you use `bsp-switch` to switch to Bloop.
       |""".stripMargin,
    "[string], name of the build server."
  )

  val BspSwitch = new Command(
    "bsp-switch",
    "Switch build server",
    """|Prompt the user to select a new build server to connect to.
       |
       |This command does nothing in case there are less than two installed build
       |servers on the computer. In case the user has multiple BSP servers installed
       |then Metals will prompt the user to select which server to use.
       |""".stripMargin
  )

  val StartDebugAdapter = new Command(
    "debug-adapter-start",
    "Start debug adapter",
    "Start debug adapter",
    s"""|DebugSessionParameters object
        |Example:
        |```json
        |{
        |  "targets": ["mybuild://workspace/foo/?id=foo"],
        |   dataKind: "${b.DebugSessionParamsDataKind.SCALA_MAIN_CLASS}",
        |   data: {
        |      className: "com.foo.App"
        |   }
        |}
        |```
        |
        |or DebugUnresolvedMainClassParams object
        |Example:
        |```json
        |{
        |   mainClass: "com.foo.App",
        |   buildTarget: "foo",
        |   args: ["bar"],
        |   jvmOptions: ["-Dfile.encoding=UTF-16"],
        |   env: {"NUM" : "123"},
        |   envFile: ".env"
        |}
        |```
        |
        |or DebugUnresolvedTestClassParams object
        |Example:
        |```json
        |{
        |   testClass: "com.foo.FooSuite",
        |   buildTarget: "foo"
        |}
        |```
        |""".stripMargin
  )

  val PresentationCompilerRestart = new Command(
    "presentation-compiler-restart",
    "Restart presentation compiler",
    """|Restart running presentation compiler instances.
       |
       |Metals automatically restarts the presentation compiler after every successful compilation
       |in the build tool so this command should not be needed for normal usage. Please report
       |an issue if you need to use this command.
       |""".stripMargin
  )

  val AnalyzeStacktrace = new Command(
    "analyze-stacktrace",
    "Analyze stacktrace",
    """|Converts provided stacktrace in the parameter to a format that contains links
       |to locations of places where the exception was raised.
       |
       |If the configuration parameter of the client (support-commands-in-html) is true
       |then client is requested to display html with links
       |already pointing to proper locations in user codebase.
       |Otherwise client will display simple scala file
       |but with code lenses that direct user to proper location in codebase.
       |""".stripMargin,
    "[string], where the string is a stacktrace."
  )

  val GotoSymbol = new Command(
    "goto",
    "Goto location for symbol",
    """|Move the cursor to the definition of the argument symbol.
       |
       |""".stripMargin,
    "[string], where the string is a SemanticDB symbol."
  )

  val GotoPosition = new Command(
    "goto-position",
    "Goto location for position",
    """|Move the cursor to the location provided in arguments.
       |It simply forwards request to client.
       |
       |""".stripMargin,
    "[location], where the location is a lsp location object."
  )

  val GotoSuperMethod = new Command(
    "goto-super-method",
    "Go to super method/field definition",
    """|Jumps to super method/field definition of a symbol under cursor according to inheritance rules.
       |When A {override def x()} <:< B <:< C {def x()} and on method 'A.x' it will jump directly to 'C.x'
       |as method x() is not overridden in B.
       |If symbol is a reference of a method it will jump to a definition.
       |If symbol under cursor is invalid or does not override anything then command is ignored.
       |
       |Note: document in json argument must be absolute path.
       |""".stripMargin,
    """|
       |Object with `document` and `position`
       |
       |Example:
       |```json
       |{
       |  document: "file:///home/dev/foo/Bar.scala",
       |  position: {line: 5, character: 12}
       |}
       |```
       |""".stripMargin
  )

  val SuperMethodHierarchy = new Command(
    "super-method-hierarchy",
    "Go to super method/field definition in hierarchy",
    """|When user executes this command it will calculate inheritance hierarchy of a class that contains given method.
       |Then it will filter out classes not overriding given method and a list using 'metalsQuickPick' will be
       |displayed to which super method user would like to go to.
       |Command has no effect on other symbols than method definition.
       |QuickPick will show up only if more than one result is found.
       |
       |Note: document in json argument must be absolute path.
       |""".stripMargin,
    """|Object with `document` and `position`
       |
       |Example:
       |```json
       |{
       |  document: "file:///home/dev/foo/Bar.scala",
       |  position: {line: 5, character: 12}
       |}
       |```
       |""".stripMargin
  )

  val ResetChoicePopup = new Command(
    "reset-choice",
    "Reset Choice Popup",
    """|ResetChoicePopup command allows you to reset a decision you made about different settings.
       |E.g. If you choose to import workspace with sbt you can decide to reset and change it again.
       |
       |Provided string is optional but if present it must be one of defined in `PopupChoiceReset.scala`
       |If a choice is not provided it will execute interactive mode where user is prompt to select
       |which choice to reset.
       |""".stripMargin,
    "[string?], where string is a choice value."
  )

  val NewScalaFile = new Command(
    "new-scala-file",
    "Create new scala file",
    """|Create and open new file with either scala class, object, trait, package object or worksheet.
       |
       |Note: requires 'metals/inputBox' capability from language client.
       |""".stripMargin,
    """|[string[]], where the first is a directory location for the new file.
       |The second and third positions correspond to the file name and file type to allow for quick
       |creation of a file if all are present.
       |""".stripMargin
  )

  val NewScalaProject = new Command(
    "new-scala-project",
    "New Scala Project",
    """|Create a new Scala project using one of the available g8 templates. 
       |This includes simple projects as well as samples for most of the popular Scala frameworks.
       |The command reuses the Metals quick pick extension to work and can function with `window/showMessageRequest`, 
       |however the experience will not be optimal in that case. Some editors might also offer to open the newly created
       |project via `openNewWindowProvider`, but it is not necessary for the main functionality to work. 
       |""".stripMargin
  )

  val CopyWorksheetOutput = new Command(
    "copy-worksheet-output",
    "Copy Worksheet Output",
    """|Copy the contents of a worksheet to your local buffer.
       |
       |Note: This command returns the contents of the worksheet, and the LSP client
       |is in charge of taking that content and putting it into your local buffer.
       |""".stripMargin,
    "[uri], the uri of the worksheet that you'd like to copy the contents of."
  )

  val ExtractMemberDefinition = new Command(
    "extract-member-definition",
    "Extract member definition",
    """|Whenever a user chooses a code action to extract a definition of a Class/Trait/Object/Enum this
       |command is later ran to extract the code and create a new file with it
       |""".stripMargin,
    """|[uri, line, character], uri of the document that the command needs to be invoked on
       |together with line number and character/column where the definition is.
       |""".stripMargin
  )

  val InsertInferredType = new Command(
    "insert-inferred-type",
    "Insert inferred type of a value",
    """|Whenever a user chooses code action to insert the inferred type this command is later ran to 
       |calculate the type and insert it in the correct location.
       |""".stripMargin,
    """|[uri, line, character], uri to the document that the command needs to be invoked on 
       |together with line number and character/column.
       |""".stripMargin
  )

  /**
   * Open the browser at the given url.
   */
  val OpenBrowser: Regex = "browser-open-url:(.*)".r
  def OpenBrowser(url: String): String = s"browser-open-url:$url"

  val GotoLog = new Command(
    "goto-log",
    "Check logs",
    "Open the Metals logs to troubleshoot issues."
  )

  val OpenIssue = new Command(
    OpenBrowser("https://github.com/scalameta/metals/issues/new/choose"),
    "Open issue on GitHub",
    "Open the Metals repository on GitHub to ask a question, report a bug or request a new feature."
  )

  val MetalsGithub = new Command(
    OpenBrowser("https://github.com/scalameta/metals"),
    "Metals on GitHub",
    "Open the Metals repository on GitHub"
  )

  val BloopGithub = new Command(
    OpenBrowser("https://github.com/scalacenter/bloop"),
    "Bloop on GitHub",
    "Open the Metals repository on GitHub"
  )

  val ChatOnGitter = new Command(
    OpenBrowser("https://gitter.im/scalameta/metals"),
    "Chat on Gitter",
    "Open the Metals channel on Gitter to discuss with other Metals users."
  )

  val ChatOnDiscord = new Command(
    OpenBrowser("https://discord.gg/RFpSVth"),
    "Chat on Discord",
    "Open the Scalameta server on Discord to discuss with other Metals users."
  )

  val ReadVscodeDocumentation = new Command(
    OpenBrowser("https://scalameta.org/metals/docs/editors/vscode.html"),
    "Read Metals documentation",
    "Open the Metals website to read the full instructions on how to use Metals with VS Code."
  )

  val ReadBloopDocumentation = new Command(
    OpenBrowser("https://scalacenter.github.io/bloop/"),
    "Read Bloop documentation",
    "Open the Bloop website to read the full instructions on how to install and use Bloop."
  )

  val ScalametaTwitter = new Command(
    OpenBrowser("https://twitter.com/scalameta"),
    "Scalameta on Twitter",
    "Stay up to date with the latest release announcements and learn new Scala code editing tricks."
  )

  val StartAmmoniteBuildServer = new Command(
    "ammonite-start",
    "Start Ammonite build server",
    "Start Ammonite build server"
  )

  val StopAmmoniteBuildServer = new Command(
    "ammonite-stop",
    "Stop Ammonite build server",
    "Stop Ammonite build server"
  )

  def all: List[Command] =
    List(
      AnalyzeStacktrace,
      BspSwitch,
      ConnectBuildServer,
      CancelCompile,
      CascadeCompile,
      CleanCompile,
      CopyWorksheetOutput,
      ExtractMemberDefinition,
      GenerateBspConfig,
      GotoPosition,
      GotoSuperMethod,
      GotoSymbol,
      ImportBuild,
      InsertInferredType,
      NewScalaFile,
      NewScalaProject,
      ResetChoicePopup,
      RestartBuildServer,
      RunDoctor,
      ScanWorkspaceSources,
      StartAmmoniteBuildServer,
      StartDebugAdapter,
      StopAmmoniteBuildServer,
      SuperMethodHierarchy
    )
}

case class DebugUnresolvedMainClassParams(
    mainClass: String,
    @Nullable buildTarget: String = null,
    @Nullable args: java.util.List[String] = null,
    @Nullable jvmOptions: java.util.List[String] = null,
    @Nullable env: java.util.Map[String, String] = null,
    @Nullable envFile: String = null
)

case class DebugUnresolvedTestClassParams(
    testClass: String,
    @Nullable buildTarget: String = null
)

case class DebugUnresolvedAttachRemoteParams(
    hostName: String,
    port: Int,
    buildTarget: String
)

case class DebugDiscoveryParams(
    path: String,
    runType: String,
    @Nullable args: java.util.List[String] = null,
    @Nullable jvmOptions: java.util.List[String] = null,
    @Nullable env: java.util.Map[String, String] = null,
    @Nullable envFile: String = null
)

package scala.meta.internal.metals

import javax.annotation.Nullable

import scala.meta.internal.metals.newScalaFile.NewFileTypes

import ch.epfl.scala.{bsp4j => b}
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentPositionParams

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
      |""".stripMargin,
  )

  val ConnectBuildServer = new Command(
    "build-connect",
    "Connect to build server",
    """Establish a new connection to the build server and reindex the workspace.
      |
      |This command can be helpful in scenarios when Metals feels unresponsive, for example
      |when reopening Metals after the computer it has been sleeping.
      |""".stripMargin,
  )

  val DisconnectBuildServer = new Command(
    "build-disconnect",
    "Disconnect from old build server",
    """Unconditionally cancel existing build server connection without reconnecting""",
  )

  val DisconnectBuildServerAndShutdown = new Command(
    "build-disconnect-and-shutdown",
    "Disconnect from build server and shut it down",
    """|Disconnect from the build server and shut it down so that its process exits.
       |Use when closing the editor if you want no leftover Bloop (or other build server) process.""".stripMargin,
  )

  val RestartBuildServer = new Command(
    "build-restart",
    "Restart build server",
    """Unconditionally stop the current running Bloop server and start a new one using Bloop launcher""",
  )

  val ResetWorkspace = new ParametrizedCommand[Boolean](
    "reset-workspace",
    "Clean and restart build server",
    """|Clean metals cache and restart build server.
       |
       |When using Bloop, clears all directories in .bloop.
       |This will ensure that Bloop will have a fully reset state.
       |""".stripMargin,
    "[boolean], force the reset without asking the user",
    default = Some(false),
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
       |""".stripMargin,
  )

  /** Decode a file e.g. javap, semanticdb */
  val DecodeFile = new ParametrizedCommand[String](
    "file-decode",
    "Decode file",
    """|Decode a file into a human readable format.
       |
       |Compilation involves various binary files that can't be read directly
       |in a text editor so they need to be decoded into a human readable format.
       |Examples include `.class` and `.semanticdb`.
       |""".stripMargin,
    """|[uri], uri of the file with any parameters required for decoding.
       |Examples:
       |- javap:
       |  ```
       |  metalsDecode:file:///somePath/someFile.java.javap
       |  metalsDecode:file:///somePath/someFile.scala.javap
       |  metalsDecode:file:///somePath/someFile.class.javap
       |  metalsDecode:file:///somePath/someFile.java.javap-verbose
       |  metalsDecode:file:///somePath/someFile.scala.javap-verbose
       |  metalsDecode:file:///somePath/someFile.class.javap-verbose
       |  ```
       |- semanticdb:
       |  ```
       |  metalsDecode:file:///somePath/someFile.java.semanticdb-compact
       |  metalsDecode:file:///somePath/someFile.java.semanticdb-detailed
       |  metalsDecode:file:///somePath/someFile.scala.semanticdb-compact
       |  metalsDecode:file:///somePath/someFile.scala.semanticdb-detailed
       |  metalsDecode:file:///somePath/someFile.java.semanticdb.semanticdb-compact
       |  metalsDecode:file:///somePath/someFile.java.semanticdb.semanticdb-detailed
       |  metalsDecode:file:///somePath/someFile.scala.semanticdb.semanticdb-compact
       |  metalsDecode:file:///somePath/someFile.scala.semanticdb.semanticdb-detailed
       |  ```
       |- tasty:
       |  ```
       |  metalsDecode:file:///somePath/someFile.scala.tasty-decoded
       |  metalsDecode:file:///somePath/someFile.tasty.tasty-decoded
       |  ```
       |- jar:
       |  ```
       |  metalsDecode:jar:file:///somePath/someFile-sources.jar!/somePackage/someFile.java
       |  ```
       |- build target:
       |  ```
       |  metalsDecode:file:///workspacePath/buildTargetName.metals-buildtarget
       |  ```
       |
       |Response:
       |```ts
       |interface DecoderResponse {
       |  requestedUri: string;
       |  value?: string;
       |  error?: string
       |}
       |```
       |""".stripMargin,
  )

  val DiscoverMainClasses = new ParametrizedCommand[DebugDiscoveryParams](
    "discover-jvm-run-command",
    "Discover main classes to run and return the object",
    """|Gets the DebugSession object that also contains a command to run in shell based
       |on JVM environment including classpath, jvmOptions and environment parameters.
       |""".stripMargin,
    """|DebugUnresolvedTestClassParams object
       |Example:
       |```json
       |{
       |    "path": "path/to/file.scala",
       |    "runType": "run"
       |}
       |```
       |
       |Response:
       |```json
       |{
       |  "targets": ["id1"],
       |  "dataKind": "scala-main-class",
       |  "data": {
       |    "class": "Foo",
       |    "arguments": [],
       |    "jvmOptions": [],
       |    "environmentVariables": [],
       |    "shellCommand": "java ..."
       |  }
       |}
       |```
       |""".stripMargin,
  )

  /** If uri is null discover all test suites, otherwise discover testcases in file */
  final case class DiscoverTestParams(
      @Nullable uri: String = null
  )
  val DiscoverTestSuites = new ParametrizedCommand[DiscoverTestParams](
    "discover-tests",
    "Discover tests",
    """
      |Discovers all tests in project or a file.
      |See ClientCommands.UpdateTestExplorer to see how response looks like.
      |""".stripMargin,
    """
      |An object with uri, when request is meant to discover test cases for uri
      |```json
      |{
      |  uri: file:///home/dev/foo/Bar.scala
      |}
      |```
      |or empty object if request is meant to discover all test suites
      |```json
      |{}
      |```
      |""".stripMargin,
  )

  val ListBuildTargets = new Command(
    "list-build-targets",
    "List build targets",
    """|Retrieve a list of build targets for the workspace.
       |""".stripMargin,
  )

  val RunDoctor = new Command(
    "doctor-run",
    "Run doctor",
    """|Open the Metals doctor to troubleshoot potential problems with the build.
       |
       |This command can be helpful in scenarios where features are not working as expected such
       |as compile errors are not appearing or completions are not correct.
       |""".stripMargin,
  )

  val ModuleStatusBarClicked = new Command(
    "module-status-bar-clicked",
    "Handle module status bar click",
    """|Handle click on the module status bar.
       |
       |For Scala CLI workspaces, offers to regenerate BSP configuration.
       |Otherwise, opens the doctor.
       |""".stripMargin,
  )

  val ZipReports = new Command(
    "zip-reports",
    "Create a zip with error reports",
    """|Creates a zip from incognito and bloop reports with additional information about build targets.
       |""".stripMargin,
  )

  val RunScalafix = new ParametrizedCommand[TextDocumentPositionParams](
    "scalafix-run",
    "Run all Scalafix Rules",
    """|Run all the supported scalafix rules in your codebase.
       |
       |If the rules are missing please add them to user configuration `metals.scalafixRulesDependencies`.
       |Their format is the coursier one https://get-coursier.io/
       |""".stripMargin,
    """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
       |""".stripMargin,
  )

  val ScalafixRunOnly = new ParametrizedCommand[RunScalafixRulesParams](
    "scalafix-run-only",
    "Run a set of Scalafix Rules",
    """|Run a set of Scalafix rules in your codebase.
       |
       |If no rules are specified, this command will prompt the user to select one from a list
       |of their project's enabled rules.
       |
       |If you want to run all rules, use the `scalafix-run` command instead.
       |""".stripMargin,
    """|RunScalafixRulesParams object
       |Example:
       |```json
       |{
       |  "textDocumentPositionParams": {
       |    "textDocument": {
       |      "uri": "path/to/file.scala"
       |    },
       |    "position": {
       |      "line": 70,
       |      "character": 33
       |    }
       |  },
       |  "rules": ["ExplicitResultTypes"]
       |}
       |```
       |""".stripMargin,
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
       |""".stripMargin,
  )

  val CleanCompile = new Command(
    "compile-clean",
    "Clean compile",
    """|Recompile all build targets in this workspace.
       |
       |By default, Metals compiles the files incrementally. In case of any compile artifacts corruption
       |this command might be run to make sure everything is recompiled correctly.
       |""".stripMargin,
  )

  val CompileTarget = new ParametrizedCommand[b.BuildTargetIdentifier](
    "compile-target",
    "CompileTarget",
    """|Compile the currently open file.
       |
       |Can be used as an explicit command in order
       |to force compilation to start and finish e.g. before executing a codeLens.
       |Returns CompileResult with field 'statusCode' being 1 for success, 2 for errors or 3 for compile cancelled.
       |""".stripMargin,
    "BuildTargetIdentifier",
  )

  val CancelCompile = new Command(
    "compile-cancel",
    "Cancel compilation",
    """Cancel the currently ongoing compilation, if any.""",
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
       | - mill-bsp
       |
       |Note: while Metals does know how to start Bloop, Bloop will be started when you trigger a build
       |import or when you use `bsp-switch` to switch to Bloop.
       |""".stripMargin,
    "[string], name of the build server.",
  )

  val BspSwitch = new Command(
    "bsp-switch",
    "Switch build server",
    """|Prompt the user to select a new build server to connect to.
       |
       |This command does nothing in case there are less than two installed build
       |servers on the computer. In case the user has multiple BSP servers installed
       |then Metals will prompt the user to select which server to use.
       |""".stripMargin,
  )

  val StartDebugAdapter = new ParametrizedCommand[b.DebugSessionParams](
    "debug-adapter-start",
    "Start debug adapter",
    "Start a new debugger session with fully specified DebugSessionParams",
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
        |""".stripMargin,
  )

  val StartMainClass = new ParametrizedCommand[DebugUnresolvedMainClassParams](
    "debug-adapter-start",
    "Start main class",
    "Start a new debugger session by resolving a main class by name and target",
    s"""|DebugUnresolvedMainClassParams object
        |Example:
        |```json
        |{
        |  "mainClass": "path/to/file.scala",
        |  "buildTarget": "metals"
        |}
        |```
        |
        |""".stripMargin,
  )

  val StartTestSuite = new ParametrizedCommand[ScalaTestSuitesDebugRequest](
    "debug-adapter-start",
    "Start test suite",
    "Start a new debugger session for a test suite, can be used to run a single test case",
    s"""|ScalaTestSuitesDebugRequest object
        |Example:
        |```json
        |{
        |  "target": "metals"
        |  "requestData" : {
        |    "suites": ["com.foo.FooSuite"],
        |    "jvmOptions": []],
        |    "environmentVariables": [],
        |   }
        |}
        |```
        |
        |""".stripMargin,
  )

  val ResolveAndStartTestSuite =
    new ParametrizedCommand[DebugUnresolvedTestClassParams](
      "debug-adapter-start",
      "Start test suite",
      "Start a new debugger session by resolving a test suite by name and possibly target.",
      s"""|DebugUnresolvedTestClassParams object
          |Example:
          |```json
          |{
          |   "testClass": "com.foo.FooSuite"
          |}
          |```
          |
          |""".stripMargin,
    )

  val StartAttach = new ParametrizedCommand[DebugUnresolvedAttachRemoteParams](
    "debug-adapter-start",
    "Attach to a running jvm process",
    "Start a new debugger session by attaching to existing jvm process.",
    s"""|DebugUnresolvedAttachRemoteParams object
        |Example:
        |```json
        |{
        |    "hostName": "localhost",
        |    "port": 1234,
        |    "buildTarget": "metals",
        |}
        |```
        |
        |""".stripMargin,
  )

  val DiscoverAndRun = new ParametrizedCommand[DebugDiscoveryParams](
    "debug-adapter-start",
    "Try to discover a test or main to run.",
    "Start a new debugger session by running the discovered test or main class.",
    s"""|DebugDiscoveryParams object
        |Example:
        |```json
        |{
        |    "path": "path/to/file.scala",
        |    "runType": "run",
        |}
        |```
        |
        |""".stripMargin,
  )

  val PresentationCompilerRestart = new Command(
    "presentation-compiler-restart",
    "Restart presentation compiler",
    """|Restart running presentation compiler instances.
       |
       |Metals automatically restarts the presentation compiler after every successful compilation
       |in the build tool so this command should not be needed for normal usage. Please report
       |an issue if you need to use this command.
       |""".stripMargin,
  )

  val AnalyzeStacktrace = new ParametrizedCommand[String](
    "analyze-stacktrace",
    "Analyze stacktrace",
    """|Converts provided stacktrace in the parameter to a format that contains links
       |to locations of places where the exception was raised.
       |
       |If the configuration parameter of the client `commandInHtmlFormat` is set
       |then client is requested to display html with links
       |already pointing to proper locations in user codebase.
       |Otherwise client will display simple scala file
       |but with code lenses that direct user to proper location in codebase.
       |""".stripMargin,
    "[string], where the string is a stacktrace.",
  )

  val ResolveStacktraceLocation = new ParametrizedCommand[String](
    "resolve-stacktrace-location",
    "Resolve stacktrace location",
    """|Resolves a single line of a stacktrace to its source location.
       |
       |Returns a Location object containing the URI and line number of the 
       |source file where the stacktrace line originated, if it can be resolved
       |to a location in the workspace.
       |""".stripMargin,
    "[string], where the string is a single line from a stacktrace.",
  )

  val MetalsPaste = new ParametrizedCommand[MetalsPasteParams](
    "metals-did-paste",
    "Add needed import statements after paste",
    """|
       |""".stripMargin,
    """|MetalsPasteParams""".stripMargin,
  )

  val ExplainDiagnostic = new ParametrizedCommand[TextDocumentPositionParams](
    "explain-diagnostic",
    "Explain Diagnostic",
    """|Run the presentation compiler with -explain flag to get detailed error explanation.
       |
       |This command compiles the file with the -explain option and renders
       |the expanded diagnostic message in a readable file.
       |""".stripMargin,
    """|Object with uri and diagnostic position info:
       |```json
       |{
       |  document: "file:///home/dev/foo/Bar.scala",
       |  position: {line: 5, character: 12}
       |}
       |```
       |""".stripMargin,
  )

  final case class ChooseClassRequest(
      textDocument: TextDocumentIdentifier,
      kind: String,
  )
  val ChooseClass = new ParametrizedCommand[ChooseClassRequest](
    "choose-class",
    "Choose class",
    """|Exists only because of how vscode virtual documents work. Usage of this command is discouraged, it'll be removed in the future,
       |when metals-vscode will implement custom editor for .tasty and .class files.
       |Shows toplevel definitions such as classes, traits, objects and toplevel methods which are defined in a given scala file.
       |Then, returns an URI pointing to the .tasty or .class file for class picked by user""".stripMargin,
    """|Object with `textDocument` and `includeInnerClasses`
       |
       |Example:
       |```json
       |{
       |  textDocument: {uri: file:///home/dev/foo/Bar.scala},
       |  kind: 'tasty' | 'class'
       |}
       |```
       |""".stripMargin,
  )

  val GotoSymbol = new ParametrizedCommand[String](
    "goto",
    "Goto location for symbol",
    """|Move the cursor to the definition of the argument symbol.
       |
       |""".stripMargin,
    "[string], where the string is a SemanticDB symbol.",
  )

  val GotoPosition = new ParametrizedCommand[Location](
    "goto-position",
    "Goto location for position",
    """|Move the cursor to the location provided in arguments.
       |It simply forwards request to client.
       |
       |""".stripMargin,
    "[location], where the location is a lsp location object.",
  )

  val GotoSuperMethod = new ParametrizedCommand[TextDocumentPositionParams](
    "goto-super-method",
    "Go to super method/field definition",
    """|Jumps to super method/field definition of a symbol under cursor according to inheritance rules.
       |When `A {override def x()} <:< B <:< C {def x()}` and on method 'A.x' it will jump directly to 'C.x'
       |as method x() is not overridden in B.
       |If symbol is a reference of a method it will jump to a definition.
       |If symbol under cursor is invalid or does not override anything then command is ignored.
       |
       |""".stripMargin,
    """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
       |""".stripMargin,
  )

  val SuperMethodHierarchy =
    new ParametrizedCommand[TextDocumentPositionParams](
      "super-method-hierarchy",
      "Go to super method/field definition in hierarchy",
      """|When user executes this command it will calculate inheritance hierarchy of a class that contains given method.
         |Then it will filter out classes not overriding given method and a list using 'metalsQuickPick' will be
         |displayed to which super method user would like to go to.
         |Command has no effect on other symbols than method definition.
         |QuickPick will show up only if more than one result is found.
         |
         |""".stripMargin,
      """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
         |""".stripMargin,
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
    "[string?], where string is a choice value.",
  )

  val ResetNotifications = new Command(
    "reset-notifications",
    "Reset notifications",
    """|ResetNotifications command allows you to reset all the dismissed notifications.
       |E.g. If you choose to dismiss build import forever, this command will make the notification show up again.
       |
       |""".stripMargin,
  )

  val NewScalaFile = new ListParametrizedCommand[String](
    "new-scala-file",
    "Create new scala file",
    s"""|Create and open new Scala file.
        |
        |The currently allowed Scala file types that can be passed in are:
        |
        | - ${NewFileTypes.ScalaFile.id} (${NewFileTypes.ScalaFile.label})
        | - ${NewFileTypes.Class.id} (${NewFileTypes.Class.label})
        | - ${NewFileTypes.CaseClass.id} (${NewFileTypes.CaseClass.label})
        | - ${NewFileTypes.Enum.id} (${NewFileTypes.Enum.label})
        | - ${NewFileTypes.Object.id} (${NewFileTypes.Object.label})
        | - ${NewFileTypes.Trait.id} (${NewFileTypes.Trait.label})
        | - ${NewFileTypes.PackageObject.id} (${NewFileTypes.PackageObject.label})
        | - ${NewFileTypes.Worksheet.id} (${NewFileTypes.Worksheet.label})
        | - ${NewFileTypes.ScalaScript.id} (${NewFileTypes.ScalaScript.label})
        |
        |Note: requires 'metals/inputBox' capability from language client.
        |""".stripMargin,
    """|[string[]], where the first is a directory location for the new file.
       |The second and third positions correspond to the file name and file type to allow for quick
       |creation of a file if all are present.
       |""".stripMargin,
  )

  val NewJavaFile = new ListParametrizedCommand[String](
    "new-java-file",
    "Create new java file",
    s"""|Create and open a new Java file.
        |
        |The currently allowed Java file types that ca be passed in are:
        |
        | - ${NewFileTypes.JavaClass.id} (${NewFileTypes.JavaClass.label})
        | - ${NewFileTypes.JavaInterface.id} (${NewFileTypes.JavaInterface.label})
        | - ${NewFileTypes.JavaEnum.id} (${NewFileTypes.JavaEnum.label})
        | - ${NewFileTypes.JavaRecord.id} (${NewFileTypes.JavaRecord.label})
        |
        |Note: requires 'metals/inputBox' capability from language client.
        |""".stripMargin,
    """|[string[]], where the first is a directory location for the new file.
       |The second and third positions correspond to the file name and file type to allow for quick
       |creation of a file if all are present.
       |""".stripMargin,
  )

  val NewScalaProject = new Command(
    "new-scala-project",
    "New Scala Project",
    """|Create a new Scala project using one of the available g8 templates.
       |This includes simple projects as well as samples for most of the popular Scala frameworks.
       |The command reuses the Metals quick pick extension to work and can function with `window/showMessageRequest`,
       |however the experience will not be optimal in that case. Some editors might also offer to open the newly created
       |project via `openNewWindowProvider`, but it is not necessary for the main functionality to work.
       |""".stripMargin,
  )

  val CopyWorksheetOutput = new ParametrizedCommand[String](
    "copy-worksheet-output",
    "Copy Worksheet Output",
    """|Copy the contents of a worksheet to your local buffer.
       |
       |Note: This command returns the contents of the worksheet, and the LSP client
       |is in charge of taking that content and putting it into your local buffer.
       |""".stripMargin,
    "[uri], the uri of the worksheet that you'd like to copy the contents of.",
  )

  val CopyFQNOfSymbol = new ParametrizedCommand[TextDocumentPositionParams](
    "copy-fqn",
    "Copy fully qualified name of symbol",
    s"""|Copy the fully qualified name of a symbol to the clipboard.
        |
        |Note: This command returns the fully qualified name of the symbol, and the LSP client
        |is in charge of taking that content and putting it into your local buffer.
        |""".stripMargin,
    """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
       |""".stripMargin,
  )

  val ExtractMemberDefinition =
    new ParametrizedCommand[TextDocumentPositionParams](
      "extract-member-definition",
      "Extract member definition",
      """|Whenever a user chooses a code action to extract a definition of a Class/Trait/Object/Enum this
         |command is later ran to extract the code and create a new file with it
         |""".stripMargin,
      """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
         |""".stripMargin,
    )

  val InsertInferredType = new ParametrizedCommand[TextDocumentPositionParams](
    "insert-inferred-type",
    "Insert inferred type of a value",
    """|Whenever a user chooses code action to insert the inferred type this command is later ran to
       |calculate the type and insert it in the correct location.
       |""".stripMargin,
    """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
       |""".stripMargin,
  )

  val InlineValue = new ParametrizedCommand[TextDocumentPositionParams](
    "inline-value",
    "Inline value",
    """|Whenever a user chooses code action to inline a value this command is later ran to
       |find all the references to choose the correct inline version (if possible to perform)
       |""".stripMargin,
    """|This command should be sent in with the LSP [`TextDocumentPositionParams`](https://microsoft.github.io/language-server-protocol/specifications/specification-current/#textDocumentPositionParams)
       |""".stripMargin,
  )

  val InsertInferredMethod =
    new ParametrizedCommand[TextDocumentPositionParams](
      "insert-inferred-method",
      "Insert inferred method",
      """|Try and create a method from the error symbol at the current position
         |where that position points to a name of form for example:
         |- `nonExisting(param)`
         |- `obj.nonExisting()`
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
         |""".stripMargin,
    )

  val GotoLog = new Command(
    "goto-log",
    "Check logs",
    "Open the Metals logs to troubleshoot issues.",
  )

  val OpenIssue = new Command(
    "open-new-github-issue",
    "Open an issue on GitHub",
    "Open the Metals repository on GitHub to ask a question or report a bug.",
  )

  val OpenFeatureRequest = new OpenBrowserCommand(
    "https://github.com/scalameta/metals-feature-requests/issues/new?template=feature-request.yml",
    "Open a feature request",
    "Open the Metals repository on GitHub to open a feature request.",
  )

  val MetalsGithub = new OpenBrowserCommand(
    "https://github.com/scalameta/metals",
    "Metals on GitHub",
    "Open the Metals repository on GitHub",
  )

  val BloopGithub = new OpenBrowserCommand(
    "https://github.com/scalacenter/bloop",
    "Bloop on GitHub",
    "Open the Metals repository on GitHub",
  )

  val ChatOnDiscord = new OpenBrowserCommand(
    "https://discord.gg/RFpSVth",
    "Chat on Discord",
    "Open the Scalameta server on Discord to discuss with other Metals users.",
  )

  val ReadVscodeDocumentation = new OpenBrowserCommand(
    "https://scalameta.org/metals/docs/editors/vscode.html",
    "Read Metals documentation",
    "Open the Metals website to read the full instructions on how to use Metals with VS Code.",
  )

  val ReadBloopDocumentation = new OpenBrowserCommand(
    "https://scalacenter.github.io/bloop/",
    "Read Bloop documentation",
    "Open the Bloop website to read the full instructions on how to install and use Bloop.",
  )

  val ScalametaTwitter = new OpenBrowserCommand(
    "https://twitter.com/scalameta",
    "Scalameta on Twitter",
    "Stay up to date with the latest release announcements and learn new Scala code editing tricks.",
  )

  val StartScalaCliServer = new Command(
    "scala-cli-start",
    "Start Scala CLI server",
    "Start Scala CLI server",
  )

  val StopScalaCliServer = new Command(
    "scala-cli-stop",
    "Stop Scala CLI server",
    "Stop Scala CLI server",
  )

  val ShowReportsForBuildTarget = new ParametrizedCommand[String](
    "show-reports-for-build-target",
    "Show error reports for a specific build target.",
    """|Show error reports for a specific build target.
       |""".stripMargin,
    "[string], the build target for which to show the error reports.",
  )

  def all: List[BaseCommand] =
    List(
      AnalyzeStacktrace,
      ResolveStacktraceLocation,
      BspSwitch,
      ModuleStatusBarClicked,
      ConnectBuildServer,
      CancelCompile,
      CascadeCompile,
      CleanCompile,
      CompileTarget,
      CopyWorksheetOutput,
      CopyFQNOfSymbol,
      DiscoverMainClasses,
      DiscoverTestSuites,
      ExtractMemberDefinition,
      GenerateBspConfig,
      GotoPosition,
      GotoSuperMethod,
      GotoSymbol,
      ImportBuild,
      InsertInferredType,
      InsertInferredMethod,
      InlineValue,
      NewScalaFile,
      NewJavaFile,
      NewScalaProject,
      PresentationCompilerRestart,
      ResetChoicePopup,
      ResetNotifications,
      RestartBuildServer,
      RunDoctor,
      RunScalafix,
      ScalafixRunOnly,
      DecodeFile,
      DisconnectBuildServer,
      DisconnectBuildServerAndShutdown,
      ListBuildTargets,
      ScanWorkspaceSources,
      StartDebugAdapter,
      StartMainClass,
      StartTestSuite,
      ResolveAndStartTestSuite,
      StartAttach,
      DiscoverAndRun,
      SuperMethodHierarchy,
      StartScalaCliServer,
      StopScalaCliServer,
      OpenIssue,
      OpenFeatureRequest,
      ZipReports,
      ResetWorkspace,
      ShowReportsForBuildTarget,
      ExplainDiagnostic,
    )

  val allIds: Set[String] = all.map(_.id).toSet

}

case class DebugUnresolvedMainClassParams(
    mainClass: String,
    @Nullable buildTarget: String = null,
    @Nullable args: java.util.List[String] = null,
    @Nullable jvmOptions: java.util.List[String] = null,
    @Nullable env: java.util.Map[String, String] = null,
    @Nullable envFile: String = null,
)

final case class ScalaTestSuitesDebugRequest(
    @Nullable target: b.BuildTargetIdentifier,
    requestData: ScalaTestSuites,
)

final case class ScalaTestSuites(
    suites: java.util.List[ScalaTestSuiteSelection],
    jvmOptions: java.util.List[String],
    environmentVariables: java.util.List[String],
)

final case class ScalaTestSuiteSelection(
    className: String,
    tests: java.util.List[String],
)

case class DebugUnresolvedTestClassParams(
    testClass: String,
    @Nullable buildTarget: String = null,
    @Nullable jvmOptions: java.util.List[String] = null,
    @Nullable env: java.util.Map[String, String] = null,
    @Nullable envFile: String = null,
)

case class DebugUnresolvedAttachRemoteParams(
    hostName: String,
    port: Int,
    @Nullable buildTarget: String = null,
) {
  def buildTargetOpt: Option[String] = Option(buildTarget)
}

case class DebugDiscoveryParams(
    @Nullable path: String,
    runType: String,
    @Nullable mainClass: String = null,
    @Nullable buildTarget: String = null,
    @Nullable args: java.util.List[String] = null,
    @Nullable jvmOptions: java.util.List[String] = null,
    @Nullable env: java.util.Map[String, String] = null,
    @Nullable envFile: String = null,
    @Nullable position: Position = null,
)

case class RunScalafixRulesParams(
    textDocumentPositionParams: TextDocumentPositionParams,
    @Nullable rules: java.util.List[String] = null,
)

case class MetalsPasteParams(
    // The text document, where text was pasted.
    textDocument: TextDocumentIdentifier,
    // The range in the text document, where text was pasted.
    range: Range,
    // Content of the file after paste.
    text: String,
    // The origin document, where text was copied from.
    originDocument: TextDocumentIdentifier,
    // The origin start offset, where text was copied from.
    originOffset: Position,
)

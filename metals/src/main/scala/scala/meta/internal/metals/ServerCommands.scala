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
        |   jvmOptions: ["-Dfile.encoding=UTF-16"]
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

  val GotoLocation = new Command(
    "goto",
    "Goto location",
    """|Move the cursor to the definition of the argument symbol.
       |
       |""".stripMargin,
    "[string], where the string is a SemanticDB symbol."
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

  val NewScalaFile = new Command(
    "new-scala-file",
    "Create new scala file",
    """|Create and open new file with either scala class, object, trait, package object or worksheet.
       |
       |Note: requires 'metals/inputBox' capability from language client.
       |""".stripMargin,
    "[string], where the string is a directory location for the new file."
  )

  val NewScalaProject = new Command(
    "new-scala-project",
    "New Scala Project",
    """|Create a new Scala project using one of the available g8 templates. 
       |This includes simple projects as well as samples for most of the popular Scala frameworks.
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
    "Start an Ammonite build server",
    "Something"
  )

  val StopAmmoniteBuildServer = new Command(
    "ammonite-stop",
    "Stop Ammonite build server",
    "Something"
  )

  def all: List[Command] =
    List(
      ImportBuild,
      RestartBuildServer,
      ConnectBuildServer,
      ScanWorkspaceSources,
      RunDoctor,
      CascadeCompile,
      CancelCompile,
      BspSwitch,
      StartDebugAdapter,
      GotoLocation,
      NewScalaFile,
      NewScalaProject,
      GotoSuperMethod,
      SuperMethodHierarchy,
      StartAmmoniteBuildServer,
      StopAmmoniteBuildServer
    )
}

case class DebugUnresolvedMainClassParams(
    mainClass: String,
    @Nullable buildTarget: String = null,
    @Nullable args: java.util.List[String] = null,
    @Nullable jvmOptions: java.util.List[String] = null
)

case class DebugUnresolvedTestClassParams(
    testClass: String,
    @Nullable buildTarget: String = null
)

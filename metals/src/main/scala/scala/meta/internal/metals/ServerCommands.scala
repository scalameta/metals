package scala.meta.internal.metals

import scala.util.matching.Regex

/**
 * LSP commands supported by the Metals language server.
 */
object ServerCommands {

  val ImportBuild = Command(
    "build-import",
    "Import build",
    """Unconditionally `sbt bloopInstall` and re-connect to the build server.
      |
      |Is by default automatically managed by the language server, but sometimes it's
      |useful to manually trigger it instead.
      |""".stripMargin
  )

  val ConnectBuildServer = Command(
    "build-connect",
    "Connect to build server",
    """Unconditionally cancel existing build server connection and re-connect.
      |
      |Useful if you manually run `bloopInstall` from the sbt shell, in which
      |case this command is needed to tell metals to communicate with the bloop
      |server.
      |""".stripMargin
  )

  val ScanWorkspaceSources = Command(
    "sources-scan",
    "Scan sources",
    """|Walk all files in the workspace and index where symbols are defined.
       |
       |Is automatically run once after `initialized` notification and incrementally
       |updated on file wathching events. A language client that doesn't support
       |file watching can run this manually instead. It should not be much slower
       |than walking the entire file tree and reading `*.scala` files to string,
       |indexing itself is cheap.
       |""".stripMargin
  )

  val RunDoctor = Command(
    "doctor-run",
    "Run doctor",
    """|Open the Metals doctor to troubleshoot potential problems.
       |""".stripMargin
  )

  val CascadeCompile = Command(
    "compile-cascade",
    "Cascade compile",
    """|Compile the current file along with all build targets in this workspace that depend on it.
       |
       |By default, Metals compiles only the current build target and its dependencies when saving a file.
       |Run the cascade compile task to additionally compile the inverse dependencies of the current build target.
       |For example, if you change the API in main sources and run cascade compile then it will also compile the
       |test sources that depend on main.
       |""".stripMargin
  )

  val CancelCompile = Command(
    "compile-cancel",
    "Cancel compilation",
    """Cancel the currently ongoing compilation, if any."""
  )

  val BspSwitch = Command(
    "bsp-switch",
    "Switch build server",
    """|Prompt the user to select a new build server to connect to.
       |
       |This command does nothing in case there are less than two installed build
       |servers on the computer. In case the user has multiple BSP servers installed
       |then Metals will prompt the user to select which server to use.
       |""".stripMargin
  )

  /**
   * Open the browser at the given url.
   */
  val OpenBrowser: Regex = "browser-open-url:(.*)".r
  def OpenBrowser(url: String): String = s"browser-open-url:$url"

  def all: List[Command] = List(
    ImportBuild,
    ConnectBuildServer,
    ScanWorkspaceSources,
    RunDoctor,
    CascadeCompile,
    CancelCompile,
    BspSwitch
  )

}

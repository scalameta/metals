package scala.meta.internal.builds
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.Properties

import scala.annotation.tailrec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.control.NonFatal

import scala.meta.inputs.Input
import scala.meta.internal.bsp.BspConfigGenerationStatus.BspConfigGenerationStatus
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.metals.clients.language.ConfiguredLanguageClient
import scala.meta.internal.semver.SemVer
import scala.meta.internal.semver.SemVer.isCompatibleVersion
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position

case class SbtBuildTool(
    workspaceVersion: Option[String],
    projectRoot: AbsolutePath,
    userConfig: () => UserConfiguration,
) extends BuildTool
    with BloopInstallProvider
    with BuildServerProvider
    with VersionRecommendation {

  import SbtBuildTool._

  /**
   * Copies the official sbt runner into `.metals` and returns its path.
   *
   * sbt 1.x and sbt 2.x publish different runners, so each series has its own
   * script and `sbt-launch.jar`. The script has to live in a directory named
   * `bin` beside that jar, which is the layout of the official distribution.
   *
   * The embedded runner is used when `sbt` is not on `PATH`. On Windows it is
   * always used, because `sbt` there is a batch file rather than an executable
   * on `PATH`.
   */
  def embeddedSbtLauncher(): AbsolutePath = {
    val series = SbtBuildTool.launcherSeries(workspaceVersion)
    val bin = projectRoot
      .resolve(".metals")
      .resolve("sbt-launcher")
      .resolve(series)
      .resolve("bin")
      .toNIO
    val scriptName = if (scala.util.Properties.isWin) "sbt.bat" else "sbt"
    val script = BuildTool.copyFromResource(
      bin,
      s"sbt-launchers/$series/$scriptName",
      destination = Some(scriptName),
    )
    script.toFile.setExecutable(true)
    BuildTool.copyFromResource(
      bin,
      s"sbt-launchers/$series/sbt-launch.jar",
      destination = Some("sbt-launch.jar"),
    )
    AbsolutePath(script)
  }

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)

  def isSbt2: Boolean = workspaceVersion.exists(ScalaVersions.isSbt2Version)

  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val bloopInstallArgs = List[String](
      "-Dbloop.export-jar-classifiers=sources",
      "-Dbloop.export-meta-build=true",
      "bloopInstall",
    )
    val allArgs = composeArgs(bloopInstallArgs)
    removeLegacyGlobalPlugin()
    writeBloopPlugin(projectRoot)
    allArgs
  }

  override def cleanupStaleConfig(): Unit = {
    // no need to cleanup, the plugin deals with that
  }

  override def generateBspConfig(
      workspace: AbsolutePath,
      systemProcess: List[String] => CancelableFuture[
        BspConfigGenerationStatus
      ],
      statusBar: StatusBar,
  ): CancelableFuture[BspConfigGenerationStatus] = {
    cleanUpPlugins()
    super.generateBspConfig(workspace, systemProcess, statusBar)
  }

  def cleanUpPlugins(): Unit = {
    @tailrec
    def cleanUpMeta(root: AbsolutePath): Unit = {
      val meta = root.resolve("project")
      if (meta.exists) {
        val metalsPlugins = meta.resolve("metals.sbt")
        if (metalsPlugins.exists) metalsPlugins.delete()
        cleanUpMeta(meta)
      }
    }
    try {
      cleanUpMeta(projectRoot)
    } catch {
      case NonFatal(e) =>
        scribe.error(s"Failed to clean up sbt plugins: ${e.getMessage}")
    }
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    SbtDigest.current(projectRoot)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = BuildInfo.sbtVersion

  override def createBspFileArgs(
      workspace: AbsolutePath
  ): Option[List[String]] =
    Option.when(workspaceSupportsBsp(workspace)) {
      composeArgs(List("bspConfig"))
    }

  def shutdownBspServer(
      shellRunner: ShellRunner
  ): CancelableFuture[Int] = {
    val shutdownArgs = composeArgs(List("--client", "shutdown"))
    scribe.info(s"running ${shutdownArgs.mkString(" ")}")
    shellRunner.run(
      "Shutting down sbt server",
      shutdownArgs,
      projectRoot,
      true,
      userConfig().javaHome,
    )
  }

  private def findSbtInPath(): Option[String] = {
    // on Windows sbt is not an executable
    // look: https://github.com/scalameta/metals/issues/6104
    if (scala.util.Properties.isWin) None
    else {
      val envPaths =
        Option(System.getenv("PATH")) match {
          case Some(paths) => paths.split(":").toList
          case None => Nil
        }

      val allPaths = projectRoot :: envPaths.map(AbsolutePath(_))
      allPaths.collectFirst { path =>
        path.resolve("sbt") match {
          case sbtPath if sbtPath.exists && sbtPath.isFile => sbtPath.toString()
        }
      }
    }
  }

  private def composeArgs(sbtArgs: List[String]): List[String] = {
    val sbtScript = userConfig().sbtScript.orElse(findSbtInPath()).map(_.trim())

    sbtScript match {
      case Some(script) if script.nonEmpty =>
        script :: sbtArgs
      case _ =>
        val isSbt2 = workspaceVersion.exists(ScalaVersions.isSbt2Version)
        // sbt 2's runner starts the native client unless --server is set.
        // --client later in the args (used for shutdown) still wins.
        val serverArgs = if (isSbt2) List("--server") else Nil
        embeddedSbtLauncher().toString() ::
          javaHomeArg :::
          serverArgs :::
          List(
            "--batch",
            "--no-colors",
            "-Djline.terminal=jline.UnsupportedTerminal",
          ) :::
          sbtArgs
    }
  }

  private def javaHomeArg: List[String] =
    JavaBinary
      .path(userConfig().javaHome)
      .map { binary =>
        val home = binary.dealias.parent.dealias.parent
        List("--java-home", home.toString())
      }
      .getOrElse(Nil)

  def workspaceSupportsBsp(workspace: AbsolutePath): Boolean = {
    loadVersion(workspace) match {
      case Some(version) =>
        scribe.info(s"sbt ${version} found for workspace.")
        val valid = isCompatibleVersion(firstVersionWithBsp, version)
        if (!valid) {
          scribe.warn(
            s"Unable to start sbt bsp server. Make sure you have sbt >= $firstVersionWithBsp defined in your build.properties file."
          )
        }
        valid
      case None =>
        scribe.warn("No sbt version can be found for sbt workspace.")
        false
    }
  }

  // We remove legacy metals.sbt file that was located in
  // global sbt plugins and which adds the plugin to each projects
  // and creates additional overhead.
  private def removeLegacyGlobalPlugin(): Unit = {
    def pluginsDirectory(version: String): AbsolutePath = {
      AbsolutePath(System.getProperty("user.home"))
        .resolve(".sbt")
        .resolve(version)
        .resolve("plugins")
    }
    val plugins =
      if (version.startsWith("0.13")) pluginsDirectory("0.13")
      else pluginsDirectory("1.0")

    val metalsFile = plugins.resolve("metals.sbt")
    Files.deleteIfExists(metalsFile.toNIO)
  }

  private def writeBloopPlugin(
      projectRoot: AbsolutePath
  ): Unit = {

    def sbtMetaDirs(
        meta: AbsolutePath,
        acc: Set[AbsolutePath],
    ): Set[AbsolutePath] = {
      if (meta.exists) {
        val files = meta.list.toList
        val hasSbtSrc = files.exists(f => f.isSbt && f.filename != "metals.sbt")
        if (hasSbtSrc) {
          val forSbtSupport = meta.resolve("project/project")
          sbtMetaDirs(meta.resolve("project"), acc + forSbtSupport)
        } else {
          acc
        }
      } else {
        acc
      }
    }

    if (!userConfig().bloopSbtAlreadyInstalled) {
      val pluginVersion =
        // from 1.4.6 Bloop is not compatible with sbt < 1.3.0
        if (SemVer.isLaterVersion(version, "1.3.0"))
          "1.4.6"
        else if (SemVer.isLaterVersion(version, "1.5.0"))
          "2.0.2"
        else if (SemVer.isLaterVersion(version, "1.9.0"))
          "2.0.10"
        else userConfig().currentBloopVersion

      val plugin = bloopPluginDetails(pluginVersion)
      val mainMeta = projectRoot.resolve("project")
      val metaMeta = projectRoot.resolve("project").resolve("project")
      sbtMetaDirs(mainMeta, Set(mainMeta, metaMeta)).foreach(dir =>
        writePlugins(dir, plugin)
      )
    }
  }

  override def toString: String = SbtBuildTool.name

  def executableName = SbtBuildTool.name

  def ensureCorrectJavaVersion(
      shellRunner: ShellRunner,
      workspace: AbsolutePath,
      languageClient: ConfiguredLanguageClient,
      restartSbtBuildServer: () => Future[Unit],
  )(implicit ex: ExecutionContext): Future[Unit] =
    if (checkCorrectJavaVersion(workspace, userConfig().javaHome)) {
      Future.successful(())
    } else {
      val promise = Promise[Unit]()
      val future: Future[Unit] =
        languageClient
          .showMessageRequest(
            Messages.SbtServerJavaHomeUpdate.params(),
            ConnectionProvider.ConnectRequestCancelationGroup,
          )
          .flatMap {
            case Messages.SbtServerJavaHomeUpdate.restart =>
              if (promise.isCompleted) {
                // executes when user chooses `restart` after the timeout
                restartSbtBuildServer()
              } else shutdownBspServer(shellRunner).future.ignoreValue
            case _ =>
              promise.trySuccess(())
              Future.successful(())
          }
      future.onComplete(promise.tryComplete(_))
      promise.future
    }
}

object SbtBuildTool {

  val name = "sbt"

  /**
   * Official runner series vendored in `sbt-launchers/<series>/`.
   * `1` is the sbt 1.13.1 runner and `2` is the sbt 2.0.11 runner.
   */
  def launcherSeries(version: Option[String]): String =
    if (version.exists(ScalaVersions.isSbt2Version)) "2" else "1"

  def launchJarResource(version: String): String =
    s"sbt-launchers/${launcherSeries(Some(version))}/sbt-launch.jar"

  // Note: (ckipp01) first version was actually 1.4.0, but there is some issues
  // with bsp discovery with it. So we instead are going with 1.4.1 being the
  // first actually supported sbt version for bsp support in Metals.
  val firstVersionWithBsp = "1.4.1"

  /**
   * Write the sbt plugin in the sbt project directory
   * Return true if the metals plugin file changed.
   */
  def writePlugins(
      projectDir: AbsolutePath,
      plugins: PluginDetails*
  ): Boolean = {
    val content =
      s"""|// format: off
          |// DO NOT EDIT! This file is auto-generated.
          |
          |${plugins.map(sbtPlugin).mkString("\n")}
          |// format: on
          |""".stripMargin
    val bytes = content.getBytes(StandardCharsets.UTF_8)
    projectDir.toFile.mkdirs()
    val metalsPluginFile = projectDir.resolve("metals.sbt")
    val pluginFileShouldChange = !metalsPluginFile.isFile ||
      !metalsPluginFile.readAllBytes.sameElements(bytes)

    if (pluginFileShouldChange) {
      Files.write(metalsPluginFile.toNIO, bytes)
    }
    pluginFileShouldChange
  }

  /**
   * Write all the plugins used by Metals when connected to sbt server:
   * - the sbt-metals plugin in the project directory
   * - the sbt-jdi-tools plugin in the project/project directory
   *
   * Return true if any plugin file changed, meaning we should reload
   */
  def writeSbtMetalsPlugins(projectRoot: AbsolutePath): Boolean = {
    val mainMeta = projectRoot.resolve("project")
    val metaMeta = projectRoot.resolve("project").resolve("project")

    val writtenPlugin =
      writePlugins(mainMeta, metalsPluginDetails, debugAdapterPluginDetails)
    val isSbt2 =
      SbtBuildTool.loadVersion(projectRoot).exists(ScalaVersions.isSbt2Version)
    if (isSbt2) {
      val writtenMeta = writePlugins(
        metaMeta,
        metalsPluginDetails,
        // sbt 2.x doesn't need sbt-jdi-tools plugin because it only runs on Java 17+ where the JDI tools are included.
      )
      writtenMeta || writtenPlugin
    } else {
      val writtenMeta =
        writePlugins(metaMeta, metalsPluginDetails, jdiToolsPluginDetails)
      writtenMeta || writtenPlugin
    }
  }

  private case class PluginDetails(
      description: Seq[String],
      artifact: String,
      resolver: Option[String],
  )

  private def sonatypeResolver(version: String): Option[String] =
    if (version.contains("SNAPSHOT"))
      Some(
        """resolvers += "Sonatype OSS Snapshots" at "https://central.sonatype.com/repository/maven-snapshots""""
      )
    else None

  /**
   * Short description and artifact for the sbt-bloop plugin
   */
  private def bloopPluginDetails(version: String): PluginDetails = {
    PluginDetails(
      description =
        Seq("This file enables sbt-bloop to create bloop config files."),
      artifact = s""""ch.epfl.scala" % "sbt-bloop" % "$version"""",
      sonatypeResolver(version),
    )
  }

  /**
   * Short description and artifact for the sbt-metals plugin
   */
  private def metalsPluginDetails: PluginDetails = {
    PluginDetails(
      Seq(
        "This plugin enables semantic information to be produced by sbt.",
        "It also adds support for debugging using the Debug Adapter Protocol",
      ),
      s""""org.scalameta" % "sbt-metals" % "${BuildInfo.metalsVersion}"""",
      sonatypeResolver(BuildInfo.metalsVersion),
    )
  }

  /**
   * The sbt-debug-adpater plugin needs sbt-jdi-tool in its meta-build
   * (in project/project/metals.sbt)
   */
  private def debugAdapterPluginDetails: PluginDetails =
    PluginDetails(
      Seq(
        "This plugin adds the BSP debug capability to sbt server."
      ),
      s""""ch.epfl.scala" % "sbt-debug-adapter" % "${BuildInfo.debugAdapterVersion}"""",
      resolver = None,
    )

  /**
   * Short description and artifact for the sbt-jdi-tools plugin
   */
  private def jdiToolsPluginDetails: PluginDetails =
    PluginDetails(
      Seq(
        "This plugin makes sure that the JDI tools are in the sbt classpath.",
        "JDI tools are used by the debug adapter server.",
      ),
      s""""com.github.sbt" % "sbt-jdi-tools" % "${BuildInfo.sbtJdiToolsVersion}"""",
      resolver = None,
    )

  /**
   * Contents of metals.sbt file that is to be installed in the workspace.
   */
  private def sbtPlugin(plugin: PluginDetails): String = {
    val resolvers = plugin.resolver.getOrElse("")
    val description = plugin.description.mkString("// ", "\n// ", "")

    s"""|$description
        |$resolvers
        |addSbtPlugin(${plugin.artifact})
        |""".stripMargin
  }

  def isSbtRelatedPath(workspace: AbsolutePath, path: AbsolutePath): Boolean = {
    val project = workspace.toNIO.resolve("project")
    val isToplevel = Set(
      workspace.toNIO,
      project,
      project.resolve("project"),
    )
    isToplevel(path.toNIO.getParent) && {
      val filename = path.toNIO.getFileName.toString
      filename.endsWith("build.properties") ||
      filename.endsWith(".sbt") ||
      filename.endsWith(".scala")
    }
  }

  def apply(
      projectRoot: AbsolutePath,
      userConfig: () => UserConfiguration,
  ): SbtBuildTool = {
    val version = loadVersion(projectRoot).map(_.toString())
    SbtBuildTool(version, projectRoot, userConfig)
  }

  def isServerRunning(projectRoot: AbsolutePath): Boolean =
    projectRoot
      .resolve("project")
      .resolve("target")
      .resolve("active.json")
      .isFile

  def loadVersion(workspace: AbsolutePath): Option[String] = {
    val props = new Properties()
    val buildproperties =
      workspace.resolve("project").resolve("build.properties")

    if (!buildproperties.isFile) None
    else {
      val in = Files.newInputStream(buildproperties.toNIO)
      try props.load(in)
      finally in.close()
      Option(props.getProperty("sbt.version"))
    }
  }

  /** `sbt.version` from the nearest `project/build.properties` above `sbtFile`. */
  def loadVersionForPath(sbtFile: AbsolutePath): Option[String] = {
    @tailrec
    def loop(dir: AbsolutePath): Option[String] = {
      loadVersion(dir) match {
        case some @ Some(_) => some
        case None =>
          dir.parentOpt match {
            case Some(parent) => loop(parent)
            case None => None
          }
      }
    }
    loop(sbtFile.parent)
  }

  def sbtInputPosAdjustment(
      originInput: Input.VirtualFile,
      autoImports: Seq[String],
  ): (Input.VirtualFile, Position => Position, AdjustLspData) = {

    val appendLineSize = autoImports.size

    val modifiedInput =
      originInput.copy(value =
        prependAutoImports(originInput.value, autoImports)
      )
    def adjustRequest(position: Position) = new Position(
      appendLineSize + position.getLine(),
      position.getCharacter(),
    )
    val adjustLspData = AdjustedLspData.create(
      pos => {
        new Position(pos.getLine() - appendLineSize, pos.getCharacter())
      },
      filterOutLocations = { loc => !loc.getUri().isSbt },
    )
    (modifiedInput, adjustRequest, adjustLspData)
  }

  def prependAutoImports(text: String, autoImports: Seq[String]): String = {
    val prepend = autoImports.mkString("", "\n", "\n")
    prepend + text
  }

  def checkCorrectJavaVersion(
      workspace: AbsolutePath,
      userJavaHome: Option[String],
  ): Boolean = {
    val bspConfigFile = workspace.resolve(Directories.bsp).resolve("sbt.json")
    if (bspConfigFile.isFile) {
      val matchesSbtJavaHome =
        for {
          text <- bspConfigFile.readTextOpt
          json = ujson.read(text)
          args <- json("argv").arrOpt
          firstArg <- args.headOption
          javaArg <- firstArg.strOpt
          // possible bug with Windows when path to java ends with /
          javaArgEscaped =
            if (scala.util.Properties.isWin)
              javaArg.replace("/", "\\")
            else javaArg
          if (javaArg.endsWith("java") || javaArg.endsWith("java.exe"))
        } yield {
          val possibleJavaBinaries =
            JavaBinary.allPossibleJavaBinaries(userJavaHome)
          val sbtJavaHomeIsCorrect =
            possibleJavaBinaries.exists(_.toString == javaArgEscaped)
          if (!sbtJavaHomeIsCorrect) {
            scribe.debug(
              s"Java binary used by sbt server $javaArgEscaped doesn't match the expected java home. Possible paths considered: $possibleJavaBinaries"
            )
          }
          sbtJavaHomeIsCorrect
        }
      matchesSbtJavaHome.getOrElse(true)
    } else true
  }
}

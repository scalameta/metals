package scala.meta.internal.builds
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise

import scala.meta.inputs.Input
import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.internal.metals._
import scala.meta.internal.semver.SemVer
import scala.meta.internal.semver.SemVer.isCompatibleVersion
import scala.meta.io.AbsolutePath

import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.services.LanguageClient

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
   * Returns path to a local copy of sbt-launch.jar.
   *
   * We use embedded sbt-launch.jar instead of user `sbt` command because we
   * can't rely on `sbt` resolving correctly when using system processes, at
   * least it failed on Windows when I tried it.
   */
  def embeddedSbtLauncher(
      outDir: Path
  ): AbsolutePath = {
    val out = BuildTool.copyFromResource(outDir, "sbt-launch.jar")
    AbsolutePath(out)
  }

  override def version: String = workspaceVersion.getOrElse(recommendedVersion)
  override def bloopInstallArgs(workspace: AbsolutePath): List[String] = {
    val bloopInstallArgs = List[String](
      "-Dbloop.export-jar-classifiers=sources",
      "bloopInstall",
    )
    val allArgs = composeArgs(bloopInstallArgs, projectRoot, tempDir)
    removeLegacyGlobalPlugin()
    writeBloopPlugin(projectRoot)
    allArgs
  }

  override def cleanupStaleConfig(): Unit = {
    // no need to cleanup, the plugin deals with that
  }

  override def digest(workspace: AbsolutePath): Option[String] =
    SbtDigest.current(projectRoot)
  override val minimumVersion: String = "0.13.17"
  override val recommendedVersion: String = BuildInfo.sbtVersion

  override def createBspFileArgs(
      workspace: AbsolutePath
  ): Option[List[String]] =
    Option.when(workspaceSupportsBsp(projectRoot)) {
      val bspConfigArgs = List[String]("bspConfig")
      val bspDir = workspace.resolve(Directories.bsp).toNIO
      composeArgs(bspConfigArgs, projectRoot, bspDir)
    }

  def shutdownBspServer(
      shellRunner: ShellRunner
  ): Future[Int] = {
    val shutdownArgs =
      composeArgs(List("--client", "shutdown"), projectRoot, projectRoot.toNIO)
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

  private def composeArgs(
      sbtArgs: List[String],
      workspace: AbsolutePath,
      sbtLauncherOutDir: Path,
  ): List[String] = {
    val sbtScript = userConfig().sbtScript.orElse(findSbtInPath()).map(_.trim())

    sbtScript match {
      case Some(script) if script.nonEmpty =>
        script :: sbtArgs
      case _ =>
        val javaArgs = List[String](
          JavaBinary(userConfig().javaHome),
          "-Djline.terminal=jline.UnsupportedTerminal",
          "-Dsbt.log.noformat=true",
          "-Dfile.encoding=UTF-8",
        )
        val jarArgs = List(
          "-jar",
          embeddedSbtLauncher(sbtLauncherOutDir).toString(),
        )
        val sbtVersion =
          if (workspaceVersion.isEmpty) List(s"-Dsbt.version=$version") else Nil
        List(
          javaArgs,
          sbtVersion,
          SbtOpts.fromWorkspaceOrEnv(workspace),
          JvmOpts.fromWorkspaceOrEnv(workspace).getOrElse(Nil),
          jarArgs,
          sbtArgs,
        ).flatten
    }
  }

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
      languageClient: LanguageClient,
      restartSbtBuildServer: () => Future[Unit],
  )(implicit ex: ExecutionContext): Future[Unit] =
    if (checkCorrectJavaVersion(workspace, userConfig().javaHome)) {
      Future.successful(())
    } else {
      val promise = Promise[Unit]()
      val future: Future[Unit] =
        languageClient
          .showMessageRequest(Messages.SbtServerJavaHomeUpdate.params())
          .asScala
          .flatMap {
            case Messages.SbtServerJavaHomeUpdate.restart =>
              if (promise.isCompleted) {
                // executes when user chooses `restart` after the timeout
                restartSbtBuildServer()
              } else shutdownBspServer(shellRunner).ignoreValue
            case _ =>
              promise.trySuccess(())
              Future.successful(())
          }
          .withTimeout(15, TimeUnit.SECONDS)
          .recover { case _: TimeoutException =>
            Future.successful(())
          }
      future.onComplete(promise.tryComplete(_))
      promise.future
    }
}

object SbtBuildTool {

  val name = "sbt"

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
    val writtenMeta =
      writePlugins(metaMeta, metalsPluginDetails, jdiToolsPluginDetails)
    writtenPlugin || writtenMeta
  }

  private case class PluginDetails(
      description: Seq[String],
      artifact: String,
      resolver: Option[String],
  )

  private def sonatypeResolver(version: String): Option[String] =
    if (version.contains("SNAPSHOT"))
      Some(
        """resolvers += "Sonatype OSS Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots""""
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
      s""""org.scala-debugger" % "sbt-jdi-tools" % "${BuildInfo.sbtJdiToolsVersion}"""",
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
          if (javaArg.endsWith("java"))
        } yield {
          val possibleJavaBinaries =
            JavaBinary.allPossibleJavaBinaries(userJavaHome)
          val sbtJavaHomeIsCorrect =
            possibleJavaBinaries.exists(_.toString == javaArg)
          if (!sbtJavaHomeIsCorrect) {
            scribe.debug(
              s"Java binary used by sbt server $javaArg doesn't match the expected java home. Possible paths considered: $possibleJavaBinaries"
            )
          }
          sbtJavaHomeIsCorrect
        }
      matchesSbtJavaHome.getOrElse(true)
    } else true
  }
}

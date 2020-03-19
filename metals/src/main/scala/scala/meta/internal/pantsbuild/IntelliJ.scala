package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import scala.sys.process._
import java.nio.charset.StandardCharsets
import scala.meta.internal.metals.{BuildInfo => V}
import java.net.URL
import com.google.gson.JsonArray
import scala.meta.internal.pantsbuild.commands.OpenOptions
import scala.meta.internal.pantsbuild.commands.{Project, RefreshCommand}
import java.nio.file.StandardOpenOption
import bloop.data.WorkspaceSettings
import bloop.io.AbsolutePath
import bloop.logging.NoopLogger

object IntelliJ {
  def launch(project: Project, open: OpenOptions): Unit = {
    val applications = Paths.get("/Applications")
    val candidates = List(
      applications.resolve("Twitter IntelliJ IDEA.app"),
      applications.resolve("Twitter IntelliJ IDEA CE.app"),
      applications.resolve("IntelliJ IDEA.app"),
      applications.resolve("IntelliJ IDEA CE.app")
    )
    def macosOpen(app: String): List[String] =
      List("open", "-a", app)
    val command = open.intellijLauncher match {
      case Some(launcher) =>
        if (launcher.endsWith(".app")) macosOpen(launcher)
        else List(launcher)
      case None =>
        candidates.find(Files.isDirectory(_)) match {
          case Some(intellij) =>
            macosOpen(intellij.toString())
          case None =>
            List("idea")
        }
    }
    val hasIdeaDirectory = project.bspRoot.resolve(".idea").isDirectory
    val openDirectory =
      // NOTE(olafur): it seems necessary to use the parent directory when there
      // is an existing .idea/ directory. This behavior was discovered by trial
      // and error. If we don't use the parent directory when there is an
      // existing idea/ directory then IntelliJ opens the project as a normal
      // directory without BSP (even if there is a .bsp/ directory).
      if (hasIdeaDirectory) project.parentRoot
      else project.bspRoot
    val exit = Process(
      command ++ List(openDirectory.toString),
      cwd = Some(openDirectory.toFile)
    ).!
    if (exit != 0) {
      scribe.error(s"failed to launch IntelliJ: '${command.mkString(" ")}'")
    }
  }

  /** The .bsp/bloop.json file is necessary for IntelliJ to automatically import the project */
  def writeBsp(project: Project, coursierBinary: Option[Path] = None): Unit = {
    val bspJson = project.root.bspJson.toNIO
    Files.createDirectories(bspJson.getParent)
    val coursier = coursierBinary.getOrElse(
      downloadCoursier(bspJson.resolveSibling("coursier"))
    )
    val targetsJson = new JsonArray()
    project.targets.foreach { target => targetsJson.add(target) }
    val newJson = s"""{
  "name": "Bloop",
  "version": "${V.bloopNightlyVersion}",
  "bspVersion": "${V.bspVersion}",
  "languages": ["scala", "java"],
  "argv": [
    "$coursier",
    "launch",
    "ch.epfl.scala:bloop-launcher-core_2.12:${V.bloopNightlyVersion}",
    "--",
    "${V.bloopVersion}"
  ],
  "timestamp": "${System.currentTimeMillis()}",
  "pantsTargets": ${targetsJson.toString}
}
"""
    Files.write(
      bspJson,
      newJson.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.CREATE
    )

    val refreshCommand = List(
      coursier.toString,
      "launch",
      s"org.scalameta:metals_2.12:${V.metalsVersion}",
      "-r",
      "sonatype:snapshots",
      "--main",
      classOf[BloopPants].getName,
      "--",
      RefreshCommand.name,
      "--workspace",
      project.common.workspace.toString,
      project.name
    )
    val configDir = AbsolutePath(project.common.bloopDirectory)
    if (!configDir.exists) configDir.createDirectories
    val currentSettings = WorkspaceSettings
      .readFromFile(configDir, NoopLogger)
      .getOrElse(WorkspaceSettings(None, None, None))
    val settings =
      currentSettings.copy(refreshProjectsCommand = Some(refreshCommand))
    WorkspaceSettings.writeToFile(configDir, settings, NoopLogger)
  }

  private def downloadCoursier(destination: Path): Path = {
    if (Files.isRegularFile(destination) && Files.isExecutable(destination)) {
      destination
    } else if (Files.exists(destination)) {
      throw new IllegalArgumentException(s"file already exists: destination")
    } else {
      val url = new URL("https://git.io/coursier-cli")
      Files.copy(
        url.openConnection().getInputStream(),
        destination
      )
      destination.toFile().setExecutable(true)
      destination
    }
  }
}

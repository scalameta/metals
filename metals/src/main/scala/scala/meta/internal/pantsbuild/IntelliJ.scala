package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import scala.sys.process._
import java.nio.charset.StandardCharsets
import scala.meta.internal.metals.{BuildInfo => V}
import java.net.URL
import com.google.gson.JsonArray
import scala.meta.internal.pantsbuild.commands.Project
import java.nio.file.StandardOpenOption

object IntelliJ {
  def launch(project: Project): Unit = {
    val applications = Paths.get("/Applications")
    val candidates = List(
      applications.resolve("Twitter IntelliJ IDEA.app"),
      applications.resolve("Twitter IntelliJ IDEA CE.app"),
      applications.resolve("IntelliJ IDEA.app"),
      applications.resolve("IntelliJ IDEA CE.app")
    )
    val command = candidates.find(Files.isDirectory(_)) match {
      case Some(intellij) =>
        List(
          "open",
          "-a",
          intellij.toString()
        )
      case None =>
        List("idea")
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
      scribe.error(s"failed to launch IntelliJ: 'binary'")
    }
  }

  /** The .bsp/bloop.json file is necessary for IntelliJ to automatically impor the project */
  def writeBsp(project: Project, coursierBinary: Option[Path] = None): Unit = {
    val bspJson = project.root.bspJson.toNIO
    Files.createDirectories(bspJson.getParent)
    val coursier = coursierBinary.getOrElse(
      downloadCoursier(bspJson.resolveSibling("coursier"))
    )
    val targetsJson = new JsonArray()
    project.targets.foreach { target =>
      targetsJson.add(target)
    }
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
  "pantsTargets": ${targetsJson.toString()}
}
"""
    Files.write(
      bspJson,
      newJson.getBytes(StandardCharsets.UTF_8),
      StandardOpenOption.TRUNCATE_EXISTING,
      StandardOpenOption.CREATE
    )
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

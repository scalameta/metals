package scala.meta.internal.pantsbuild

import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import scala.sys.process._
import java.nio.charset.StandardCharsets
import scala.meta.internal.metals.{BuildInfo => V}
import java.net.URL

object IntelliJ {
  def launch(directory: Path): Unit = {
    val applications = Paths.get("/Applications")
    val candidates = List(
      applications.resolve("Twitter IntelliJ IDEA CE.app"),
      applications.resolve("IntelliJ IDEA CE.app")
    )
    writeBsp(directory)
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
    val hasIdeaDirectory = Files.isDirectory(directory.resolve(".idea"))
    val arguments =
      if (hasIdeaDirectory) List()
      else List(directory.toString())
    val exit = Process(command ++ arguments, cwd = Some(directory.toFile())).!
    if (exit != 0) {
      scribe.error(s"failed to launch IntelliJ: 'binary'")
    }
  }

  /** The .bsp/bloop.json file is necessary for IntelliJ to automatically impor the project */
  private def writeBsp(directory: Path): Unit = {
    val bsp = Files.createDirectories(directory.resolve(".bsp"))
    val coursier = downloadCoursier(bsp.resolve("coursier"))
    Files.write(
      bsp.resolve("bloop.json"),
      s"""{
  "name": "Bloop",
  "version": "${V.bloopVersion}",
  "bspVersion": "${V.bspVersion}",
  "languages": ["scala", "java"],
  "argv": [
    "$coursier",
    "launch",
    "ch.epfl.scala:bloop-launcher-core_2.12:${V.bloopVersion}",
    "--",
    "${V.bloopVersion}"
  ]
}
""".getBytes(StandardCharsets.UTF_8)
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

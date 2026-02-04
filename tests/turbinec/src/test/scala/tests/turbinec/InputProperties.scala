package tests.turbinec

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.jdk.CollectionConverters._

case class InputProperties(
    sourceroot: Path,
    sourceDirectories: List[Path],
    classpath: List[Path],
) {
  def scala2Sources: List[Path] = {
    val dirs = sourceDirectories.filterNot(path => path.toString.contains("scala-3"))
    dirs.flatMap { dir =>
      if (Files.isDirectory(dir)) {
        val stream = Files.walk(dir)
        try {
          stream
            .filter(path => path.toString.endsWith(".scala"))
            .iterator()
            .asScala
            .toList
        } finally {
          stream.close()
        }
      } else {
        Nil
      }
    }
  }
}

object InputProperties {
  def scala2(): InputProperties =
    fromResource("metals-input.properties")

  private def fromResource(path: String): InputProperties = {
    val props = new java.util.Properties()
    val in = Option(getClass.getClassLoader.getResourceAsStream(path))
      .getOrElse(throw new IllegalArgumentException(s"no such resource: $path"))
    try props.load(in)
    finally in.close()

    def getKey(key: String): String =
      Option(props.getProperty(key)).getOrElse(throw new IllegalArgumentException(props.toString))

    InputProperties(
      sourceroot = Paths.get(getKey("sourceroot")),
      sourceDirectories = splitPaths(getKey("sourceDirectories")),
      classpath = splitPaths(getKey("classpath")),
    )
  }

  private def splitPaths(value: String): List[Path] = {
    if (value.trim.isEmpty) {
      Nil
    } else {
      value
        .split(java.io.File.pathSeparator)
        .toList
        .filter(_.nonEmpty)
        .map(Paths.get(_))
    }
  }
}

package tests

import scala.meta.internal.io.FileIO
import scala.meta.internal.io.PathIO
import scala.meta.io.AbsolutePath
import scala.meta.io.Classpath

import tests.MetalsTestEnrichments._

case class InputProperties(
    sourceroot: AbsolutePath,
    sourceDirectories: List[AbsolutePath],
    classpath: Classpath,
    dependencySources: Classpath
) {

  def scalaFiles: List[InputFile] = {
    allFiles.filter(file => PathIO.extension(file.file.toNIO) == "scala")
  }

  def allFiles: List[InputFile] = {
    for {
      directory <- sourceDirectories
      if directory.isDirectory
      file <- FileIO.listAllFilesRecursively(directory)
    } yield {
      InputFile(
        file = file,
        file.text,
        directory,
        semanticdbRelativePath = file.toRelative(sourceroot)
      )
    }
  }
}

object InputProperties {
  def default(): InputProperties = {
    val props = new java.util.Properties()
    val path = "metals-input.properties"
    val in = this.getClass.getClassLoader.getResourceAsStream(path)
    assert(in != null, s"no such resource: $path")
    try props.load(in)
    finally in.close()
    def getKey(key: String): String = {
      Option(props.getProperty(key)).getOrElse {
        throw new IllegalArgumentException(props.toString)
      }
    }
    InputProperties(
      sourceroot = AbsolutePath(getKey("sourceroot")),
      sourceDirectories = Classpath(getKey("sourceDirectories")).entries,
      classpath = Classpath(getKey("classpath")),
      dependencySources = Classpath(getKey("dependencySources"))
    )
  }

  def fromDirectory(directory: AbsolutePath): InputProperties =
    InputProperties(directory, List(directory), Classpath(Nil), Classpath(Nil))
}

package scala.meta.languageserver

import java.io.File
import java.nio.file.Files
import java.util.Properties
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath

case class CompilerConfig(
    sources: List[AbsolutePath],
    scalacOptions: List[String],
    classpath: String,
    libraryDependencies: List[ModuleID]
)

object CompilerConfig extends LazyLogging {
  def fromPath(
      path: AbsolutePath
  )(implicit cwd: AbsolutePath): CompilerConfig = {
    val input = Files.newInputStream(path.toNIO)
    try {
      val props = new Properties()
      props.load(input)
      val sources = props
        .getProperty("sources")
        .split(File.pathSeparator)
        .iterator
        .map(AbsolutePath(_))
        .toList
      val scalacOptions = props.getProperty("scalacOptions").split(" ").toList
      val classpath = props.getProperty("classpath")
      val libraryDependencies =
        ModuleID.fromString(props.getProperty("libraryDependencies"))
      CompilerConfig(
        sources,
        scalacOptions,
        classpath,
        libraryDependencies.toList
      )
    } finally input.close()
  }
}

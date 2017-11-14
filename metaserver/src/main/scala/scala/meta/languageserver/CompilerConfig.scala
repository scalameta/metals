package scala.meta.languageserver

import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.io.AbsolutePath
import org.langmeta.io.Classpath

/**
 * Configuration to load up a presentation compiler.
 *
 * In sbt, one compiler config typically corresponds to one project+config.
 * For example one sbt project with test/main/it configurations has three
 * CompilerConfig.
 *
 * @param sources list of source files for this project
 * @param scalacOptions space separated list of flags to pass to the Scala compiler
 * @param dependencyClasspath File.pathSeparated list of *.jar and classDirectories.
 *                  Includes both dependencyClasspath and classDirectory.
 * @param classDirectory The output directory where *.class files are emitted
 *                       for this project.
 * @param sourceJars File.pathSeparated list of *-sources.jar from the
 *                   dependencyClasspath.
 */
case class CompilerConfig(
    sources: List[AbsolutePath],
    scalacOptions: List[String],
    classDirectory: AbsolutePath,
    dependencyClasspath: List[AbsolutePath],
    sourceJars: List[AbsolutePath]
) {
  override def toString: String =
    s"CompilerConfig(" +
      s"sources={+${sources.length}}, " +
      s"scalacOptions=${scalacOptions.mkString(" ")}, " +
      s"dependencyClasspath={+${dependencyClasspath.length}}, " +
      s"classDirectory=$classDirectory, " +
      s"sourceJars={+${sourceJars.length}})"
  def classpath: String =
    (classDirectory :: dependencyClasspath).mkString(File.pathSeparator)
}

object CompilerConfig extends LazyLogging {

  def jdkSourcePath: Option[AbsolutePath] =
    sys.env.get("JAVA_HOME").map(AbsolutePath(_).resolve("src.zip"))

  def jdkSources: Option[AbsolutePath] =
    for {
      path <- jdkSourcePath
      if Files.isRegularFile(path.toNIO)
    } yield path

  def fromPath(
      path: AbsolutePath
  )(implicit cwd: AbsolutePath): CompilerConfig = {
    val input = Files.newInputStream(path.toNIO)
    try {
      val props = new Properties()
      props.load(input)
      fromProperties(props)
    } finally input.close()
  }

  def fromProperties(
      props: Properties
  )(implicit cwd: AbsolutePath): CompilerConfig = {
    val sources = props
      .getProperty("sources")
      .split(File.pathSeparator)
      .iterator
      .map(AbsolutePath(_))
      .toList
    val scalacOptions =
      props.getProperty("scalacOptions").split(" ").toList
    val dependencyClasspath =
      Classpath(props.getProperty("dependencyClasspath")).shallow
    val sourceJars = {
      val result = Classpath(props.getProperty("sourceJars")).shallow
      jdkSources.fold(result)(_ :: result)
    }
    val classDirectory =
      AbsolutePath(props.getProperty("classDirectory"))
    CompilerConfig(
      sources,
      scalacOptions,
      classDirectory,
      dependencyClasspath,
      sourceJars
    )
  }
}

package scala.meta.metals.compiler

import java.nio.file.Files
import java.nio.file.Paths
import java.util.Properties
import scala.tools.nsc.settings.ScalaVersion
import scala.tools.nsc.settings.SpecificScalaVersion
import com.typesafe.scalalogging.LazyLogging
import org.langmeta.internal.io.PathIO
import org.langmeta.io.AbsolutePath
import org.langmeta.io.RelativePath
import scala.util.control.NonFatal

/**
 * Configuration to load up a presentation compiler.
 *
 * In sbt, one compiler config typically corresponds to one project+config.
 * For example one sbt project with test/main/it configurations has three
 * CompilerConfig.
 *
 * @param sources list of source files for this project
 * @param unmanagedSourceDirectories list of directories that are manually edited, not auto-generated
 * @param managedSourceDirectories list of directories that contain auto-generated code
 * @param scalacOptions space separated list of flags to pass to the Scala compiler
 * @param dependencyClasspath File.pathSeparated list of *.jar and classDirectories.
 *                  Includes both dependencyClasspath and classDirectory.
 * @param classDirectory The output directory where *.class files are emitted
 *                       for this project.
 * @param sourceJars File.pathSeparated list of *-sources.jar from the
 *                   dependencyClasspath.
 * @param origin Path to this .compilerconfig file.
 */
case class CompilerConfig(
    sources: List[AbsolutePath],
    unmanagedSourceDirectories: List[AbsolutePath],
    managedSourceDirectories: List[AbsolutePath],
    scalacOptions: List[String],
    classDirectory: AbsolutePath,
    dependencyClasspath: List[AbsolutePath],
    sourceJars: List[AbsolutePath],
    origin: AbsolutePath,
    scalaVersion: SpecificScalaVersion
) {
  lazy val sourceDirectories: List[AbsolutePath] =
    unmanagedSourceDirectories ++ managedSourceDirectories
  override def toString: String =
    s"CompilerConfig(" +
      s"sources={+${sources.length}}, " +
      s"scalacOptions=${scalacOptions.mkString(" ")}, " +
      s"dependencyClasspath={+${dependencyClasspath.length}}, " +
      s"classDirectory=$classDirectory, " +
      s"sourceJars={+${sourceJars.length}}, " +
      s"origin=$origin, " +
      s"scalaVersion=${scalaVersion.unparse})"

  def classpath: String =
    (classDirectory :: dependencyClasspath).mkString(java.io.File.pathSeparator)
}

object CompilerConfig extends LazyLogging {
  private val relativeDir: RelativePath =
    RelativePath(".metals").resolve("buildinfo")

  def dir(cwd: AbsolutePath): AbsolutePath =
    cwd.resolve(relativeDir)

  object File {
    def unapply(path: RelativePath): Boolean = {
      path.toNIO.startsWith(relativeDir.toNIO) &&
      PathIO.extension(path.toNIO) == "properties"
    }
  }

  def jdkSources: Option[AbsolutePath] =
    for {
      javaHome <- sys.props.get("java.home")
      jdkSources = Paths.get(javaHome).getParent.resolve("src.zip")
      if Files.isRegularFile(jdkSources)
    } yield AbsolutePath(jdkSources)

  def fromPath(
      path: AbsolutePath
  )(implicit cwd: AbsolutePath): CompilerConfig = {
    val input = Files.newInputStream(path.toNIO)
    try {
      val props = new Properties()
      props.load(input)
      fromProperties(props, path)
    } catch {
      case NonFatal(e) =>
        logger.error(s"Failed to parse $path", e)
        throw new IllegalArgumentException(path.toString(), e)
    } finally {
      input.close()
    }
  }

  def fromProperties(
      props: Properties,
      origin: AbsolutePath
  )(implicit cwd: AbsolutePath): CompilerConfig = {

    def getPaths(implicit name: sourcecode.Name): List[AbsolutePath] = {
      Option(props.getProperty(name.value)) match {
        case None =>
          logger.warn(s"$origin: Missing key '${name.value}'")
          Nil
        case Some(paths) =>
          paths
            .split(java.io.File.pathSeparator)
            .iterator
            .map(AbsolutePath(_))
            .toList
      }
    }
    val sources = getPaths
    val unmanagedSourceDirectories = getPaths
    val managedSourceDirectories = getPaths
    val scalacOptions = props.getProperty("scalacOptions").split(" ").toList
    val dependencyClasspath = getPaths
    val sourceJars = getPaths
    val classDirectory = AbsolutePath(props.getProperty("classDirectory"))
    val scalaVersion = ScalaVersion(props.getProperty("scalaVersion"))
      .asInstanceOf[SpecificScalaVersion]
    CompilerConfig(
      sources,
      unmanagedSourceDirectories,
      managedSourceDirectories,
      scalacOptions,
      classDirectory,
      dependencyClasspath,
      sourceJars,
      origin,
      scalaVersion
    )
  }
}

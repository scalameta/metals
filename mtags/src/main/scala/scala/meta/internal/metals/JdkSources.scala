package scala.meta.internal.metals

import java.net.URI
import java.nio.file.Paths
import java.util.Locale

import scala.util.Failure
import scala.util.Properties
import scala.util.Success
import scala.util.Try

import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

import org.slf4j.LoggerFactory

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  val zipFileName = "src.zip"
  private val sources = RelativePath(Paths.get(zipFileName))
  private val libSources = RelativePath(Paths.get("lib")).resolve(sources)

  private val logger = LoggerFactory.getLogger(JdkSources.getClass)

  def apply(
      userJavaHome: Option[String] = None
  ): Either[NoSourcesAvailable, AbsolutePath] = {
    val paths = candidates(userJavaHome)
    paths.find(_.isFile) match {
      case Some(value) => Right(value.dealias)
      case None => Left(NoSourcesAvailable(paths))
    }
  }

  /**
   * Reads a java home written either way: `/jdk/Home` or `file:///jdk/Home`.
   *
   * The `file:` spelling reaches Metals two ways: from a build server, whose
   * `javaHome` BSP types as a URI, and from the Gradle importer, which writes
   * one into `MbtNamespace.javaHome`. `AbsolutePath` alone would read it as a
   * relative path whose first element is the scheme, `file:`, so the JDK would
   * go unnoticed.
   */
  private def fromString(path: String): Option[AbsolutePath] = {
    Option(path).filter(_.nonEmpty).flatMap { str =>
      // Inside the `Try`, since `URI.create` rejects a `file:` URI that no path
      // can hold: `file://host/jdk` names `/jdk` on a machine called `host`.
      Try {
        // `FILE:` as well: a URI scheme is case-insensitive, and `Paths.get`
        // reads any case. `Locale.ROOT`, since a Turkish locale lowercases
        // `FILE:` to `fıle:`.
        if (str.toLowerCase(Locale.ROOT).startsWith("file:"))
          AbsolutePath(Paths.get(URI.create(str)))
        else AbsolutePath(str)
      } match {
        case Failure(exception) =>
          logger.warn(
            s"Failed to parse java home path $str: ${exception.getMessage}"
          )
          None
        case Success(value) => Some(value)
      }
    }
  }

  def defaultJavaHome(userJavaHome: Option[String]): List[AbsolutePath] = {
    userJavaHome.flatMap(fromString).toList ++
      fromString(System.getenv("JAVA_HOME")).toList ++
      fromString(System.getProperty("java.home")).toList
  }

  def envVariables(userJavaHome: Option[String]): Map[String, String] = {
    JdkSources
      .defaultJavaHome(userJavaHome)
      .flatMap(path =>
        List(
          (path, path.resolve("bin")),
          (path, path.resolve("jre/bin"))
        )
      )
      .collectFirst {
        case (javaHome, javaBin) if javaHome.exists && javaBin.exists =>
          val variableName = if (Properties.isWin) "Path" else "PATH"
          val oldPath = System.getenv().getOrDefault(variableName, "")
          val newPath =
            if (oldPath.isEmpty()) javaBin.toString()
            else {
              val sep = if (Properties.isWin) ";" else ":"
              s"$javaBin$sep$oldPath"
            }
          Map(
            "JAVA_HOME" -> javaHome.toString(),
            variableName -> newPath
          )
      }
      .getOrElse(Map.empty)
  }

  private def candidates(userJavaHome: Option[String]): List[AbsolutePath] = {
    def isJdkCandidate(path: AbsolutePath): Boolean = {
      def containsJre = path.resolve("jre").exists
      def containsRelease = path.resolve("release").exists
      val name = path.filename.toString
      name.contains("jdk") || // e.g. jdk-8, java-openjdk-11
      containsJre ||
      containsRelease
    }

    for {
      javaHome <- defaultJavaHome(userJavaHome)
      jdkHome = {
        if (isJdkCandidate(javaHome)) {
          Nil
        } else {
          // In case java.home points to the JRE instead of the JDK,
          // try to find jdk among its siblings
          javaHome.parent.list
            .filter(isJdkCandidate)
            .toArray[AbsolutePath]
            .sortBy(_.filename)
            .toList
        }
      }
      jdk <- jdkHome ++ List(javaHome.parent, javaHome)
      src <- List(sources, libSources).map(jdk.resolve)
    } yield src
  }

  case class NoSourcesAvailable(candidates: List[AbsolutePath])
}

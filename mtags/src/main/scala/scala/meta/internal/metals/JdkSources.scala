package scala.meta.internal.metals

import java.nio.file.Paths
import java.util.logging.Logger

import scala.util.Failure
import scala.util.Success
import scala.util.Try

import scala.meta.internal.mtags.ScalametaCommonEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  val zipFileName = "src.zip"
  private val sources = RelativePath(Paths.get(zipFileName))
  private val libSources = RelativePath(Paths.get("lib")).resolve(sources)

  private val logger: Logger =
    Logger.getLogger(JdkSources.getClass().getName)

  def apply(
      userJavaHome: Option[String] = None
  ): Either[NoSourcesAvailable, AbsolutePath] = {
    val paths = candidates(userJavaHome)
    paths.find(_.isFile) match {
      case Some(value) => Right(value)
      case None => Left(NoSourcesAvailable(paths))
    }
  }

  private def fromString(path: String): Option[AbsolutePath] = {
    Option(path).filter(_.nonEmpty).flatMap { str =>
      Try(AbsolutePath(str)) match {
        case Failure(exception) =>
          logger.warning(
            s"Failed to parse java home path $str: ${exception.getMessage}"
          )
          None
        case Success(value) =>
          if (value.toString().contains("unit")) throw new Exception
          Some(value)
      }
    }
  }

  def defaultJavaHome(userJavaHome: Option[String]): List[AbsolutePath] = {
    userJavaHome.flatMap(fromString).toList ++
      fromString(System.getenv("JAVA_HOME")).toList ++
      fromString(System.getProperty("java.home")).toList
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

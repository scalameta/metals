package scala.meta.internal.metals

import java.nio.file.Paths

import scala.meta.internal.mtags.MtagsEnrichments._
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  val zipFileName = "src.zip"
  private val sources = RelativePath(Paths.get(zipFileName))
  private val libSources = RelativePath(Paths.get("lib")).resolve(sources)

  def apply(
      userJavaHome: Option[String] = None
  ): Either[NoSourcesAvailable, AbsolutePath] = {
    val paths = candidates(userJavaHome)
    paths.find(_.isFile) match {
      case Some(value) => Right(value)
      case None => Left(NoSourcesAvailable(paths))
    }
  }

  def defaultJavaHome: Option[String] = {
    Option(System.getenv("JAVA_HOME")).orElse(
      Option(System.getProperty("java.home"))
    )
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
      javaHomeString <- userJavaHome.orElse(defaultJavaHome).toList
      javaHome = AbsolutePath(javaHomeString)
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

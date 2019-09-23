package scala.meta.internal.metals

import java.nio.file.Paths
import scala.meta.io.AbsolutePath
import scala.meta.io.RelativePath
import scala.meta.internal.mtags.MtagsEnrichments._

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  private val sources = RelativePath(Paths.get("src.zip"))
  private val libSources = RelativePath(Paths.get("lib")).resolve(sources)

  def apply(userJavaHome: Option[String] = None): Option[AbsolutePath] = {
    candidates(userJavaHome).headOption
  }

  def defaultJavaHome: Option[String] = {
    Option(System.getenv("JAVA_HOME")).orElse(
      Option(System.getProperty("java.home"))
    )
  }

  private def candidates(userJavaHome: Option[String]): List[AbsolutePath] = {
    def isJdkCandidate(path: AbsolutePath): Boolean = {
      def containsJre = path.resolve("jre").exists
      val name = path.filename.toString
      name.contains("jdk") || containsJre //e.g. jdk-8, java-openjdk-11
    }

    for {
      javaHomeString <- userJavaHome.orElse(defaultJavaHome).toList
      javaHome = AbsolutePath(Paths.get(javaHomeString))
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
      if src.isFile
    } yield src
  }

  def getOrThrow(): AbsolutePath = {
    val list = candidates(None)
    list.find(_.isFile).getOrElse {
      val tried = list.mkString("\n")
      throw new NoSuchElementException(s"JDK src.zip. Tried\n:$tried")
    }
  }
}

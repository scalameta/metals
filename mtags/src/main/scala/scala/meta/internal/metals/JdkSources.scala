package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import scala.meta.io.AbsolutePath

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  private val sources = Paths.get("src.zip")
  private val libSources = Paths.get("lib").resolve(sources)

  def apply(userJavaHome: Option[String] = None): Option[AbsolutePath] = {
    candidates(userJavaHome).headOption
  }

  def defaultJavaHome: Option[String] = {
    Option(System.getenv("JAVA_HOME")).orElse(
      Option(System.getProperty("java.home"))
    )
  }

  private def candidates(userJavaHome: Option[String]): List[AbsolutePath] = {
    def isJdkCandidate(path: Path): Boolean = {
      def containsJre = Files.exists(path.resolve("jre"))
      val name = path.getFileName.toString
      name.contains("jdk") || containsJre //e.g. jdk-8, java-openjdk-11
    }

    for {
      javaHomeString <- userJavaHome.orElse(defaultJavaHome).toList
      javaHome = Paths.get(javaHomeString)
      jdkHome = {
        if (isJdkCandidate(javaHome)) {
          Nil
        } else {
          // In case java.home points to the JRE instead of the JDK,
          // try to find jdk among its siblings
          Files
            .list(javaHome.getParent)
            .filter(isJdkCandidate)
            .toArray[Path](size => new Array(size))
            .sortBy(_.getFileName)
            .toList
        }
      }
      jdk <- jdkHome ++ List(javaHome.getParent, javaHome)
      src <- List(sources, libSources).map(jdk.resolve)
      if Files.isRegularFile(src)
    } yield AbsolutePath(src)
  }

  def getOrThrow(): AbsolutePath = {
    val list = candidates(None)
    list.find(_.isFile).getOrElse {
      val tried = list.mkString("\n")
      throw new NoSuchElementException(s"JDK src.zip. Tried\n:$tried")
    }
  }
}

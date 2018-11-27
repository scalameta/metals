package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Paths
import scala.meta.io.AbsolutePath
import MetalsEnrichments._

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  def apply(userJavaHome: Option[String] = None): Option[AbsolutePath] = {
    candidates(userJavaHome).find(_.isFile)
  }

  def defaultJavaHome: Option[String] = {
    Option(System.getProperty("JAVA_HOME")).orElse(
      Option(System.getProperty("java.home"))
    )
  }

  def candidates(userJavaHome: Option[String]): List[AbsolutePath] = {
    for {
      javaHomeString <- userJavaHome.orElse(defaultJavaHome).toList
      javaHome = Paths.get(javaHomeString)
      jdkHome = {
        if (javaHome.getFileName.toString.startsWith("jdk")) {
          Nil
        } else {
          // In case java.home points to the JRE instead of the JDK, try
          // to pick a sibling directory that starts with jdk*.
          val ls = Files.list(javaHome.getParent)
          try {
            ls.iterator()
              .asScala
              .filter(_.getFileName.toString.startsWith("jdk"))
              .toArray
              .sortBy(_.getFileName.toString)
              .toList
          } finally ls.close()
        }
      }
      src <- jdkHome ++ List(
        javaHome.getParent,
        javaHome
      ).flatMap { dir =>
        List(
          dir.resolve("src.zip"),
          dir.resolve("lib").resolve("src.zip")
        )
      }
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

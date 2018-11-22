package scala.meta.internal.metals

import java.nio.file.Files
import java.nio.file.Paths
import scala.meta.io.AbsolutePath
import MetalsEnrichments._

/**
 * Locates zip file on disk that contains the source code for the JDK.
 */
object JdkSources {
  def apply(): Option[AbsolutePath] = {
    candidates.find(_.isFile)
  }

  def candidates: List[AbsolutePath] = {
    for {
      javaHomeString <- Option(System.getProperty("java.home")).toList
      javaHome = Paths.get(javaHomeString)
      jdkHome = {
        if (javaHome.getFileName.toString.startsWith("jdk")) {
          Nil
        } else {
          // In case java.home points to the JRE instead of the JDK, try
          // to pick a sibling directory that starts with jdk*.
          val ls = Files.list(javaHome.getParent)
          val jdk =
            try {
              ls.iterator()
                .asScala
                .filter(_.getFileName.toString.startsWith("jdk"))
                .toArray
                .sortBy(_.getFileName.toString)
                .lastOption
                .toList
            } finally ls.close()
          jdk.map(_.resolve("src.zip"))
        }
      }
      src <- jdkHome ++ List(
        javaHome.getParent.resolve("src.zip"),
        javaHome.resolve("src.zip")
      )
    } yield AbsolutePath(src)
  }
  def getOrThrow(): AbsolutePath = {
    val list = candidates
    list.find(_.isFile).getOrElse {
      val tried = list.mkString("\n")
      throw new NoSuchElementException(s"JDK src.zip. Tried\n:$tried")
    }
  }
}

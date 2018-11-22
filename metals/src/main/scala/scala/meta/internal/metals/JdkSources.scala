package scala.meta.internal.metals
import java.nio.file.Paths
import scala.meta.io.AbsolutePath

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
      src <- List(
        javaHome.getParent.resolve("src.zip"),
        javaHome.resolve("src.zip")
      )
    } yield AbsolutePath(src)
  }
  def getOrThrow(): AbsolutePath = {
    val list = candidates
    list.headOption.getOrElse {
      val tried = list.mkString("\n")
      throw new NoSuchElementException(s"JDK src.zip. Tried\n:$tried")
    }
  }
}

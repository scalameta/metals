package scala.meta.internal.metals

import scala.meta.internal.metals.MetalsEnrichments._
import scala.meta.io.AbsolutePath

object JavaBinary {

  /**
   * Returns absolute path to the `java` binary of the configured Java Home directory.
   */
  def apply(javaHome: Option[String]): String = {
    apply(javaHome, "java")
  }

  /**
   * Returns absolute path to the `binaryName` binary of the configured Java Home directory.
   */
  def apply(javaHome: Option[String], binaryName: String): String = {
    path(javaHome, binaryName)
      .map(_.toString())
      .getOrElse(binaryName)
  }

  def path(
      javaHome: Option[String],
      binaryName: String = "java",
  ): Option[AbsolutePath] = {
    JdkSources
      .defaultJavaHome(javaHome)
      .flatMap(home =>
        List(binaryName, binaryName + ".exe").map(home.resolve("bin").resolve)
      )
      .map { path =>
        scribe.debug(s"""java binary path: $path
                        |path exists: ${path.exists}""".stripMargin)
        path
      }
      .collectFirst {
        case path if path.exists => path
      }
  }
}

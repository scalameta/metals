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

  private def fromAbsolutePath(javaPath: Iterable[AbsolutePath]) = {
    javaPath
      .flatMap(home => List(home.resolve("bin"), home.resolve("bin/jre")))
      .flatMap(bin => List("java", "java.exe").map(bin.resolve))
      .withFilter(_.exists)
      .flatMap(binary => List(binary.dealias, binary))
  }

  def javaBinaryFromPath(
      javaHome: Option[String]
  ): Option[AbsolutePath] =
    fromAbsolutePath(javaHome.map(_.toAbsolutePath)).headOption

  def allPossibleJavaBinaries(
      javaHome: Option[String]
  ): Iterable[AbsolutePath] = {
    fromAbsolutePath(
      JdkSources
        .defaultJavaHome(javaHome)
    )
  }
}

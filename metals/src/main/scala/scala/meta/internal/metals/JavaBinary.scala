package scala.meta.internal.metals

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
    javaHome
      .orElse(JdkSources.defaultJavaHome)
      .map(AbsolutePath(_).resolve("bin").resolve(binaryName).toString())
      .getOrElse(binaryName)
  }

}

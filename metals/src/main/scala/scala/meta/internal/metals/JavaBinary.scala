package scala.meta.internal.metals

import scala.meta.io.AbsolutePath

object JavaBinary {

  /**
   * Returns absolute path to the `java` binary of the configured Java Home directory.
   */
  def apply(javaHome: Option[String]): String = {
    javaHome
      .orElse(JdkSources.defaultJavaHome)
      .map(AbsolutePath(_).resolve("bin").resolve("java").toString())
      .getOrElse("java")
  }

}

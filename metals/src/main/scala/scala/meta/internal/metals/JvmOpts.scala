package scala.meta.internal.metals
// See NOTICE.md, this file contains parts that are derived from
// IntelliJ Scala, in particular JvmOpts.scala:
// https://github.com/JetBrains/intellij-scala/blob/e2c57778bb302a6f2f93e2628f6762ecbf76fb3a/scala/scala-impl/src/org/jetbrains/sbt/project/structure/JvmOpts.scala

import java.nio.charset.StandardCharsets
import java.nio.file.Files

import scala.meta.internal.io.FileIO
import scala.meta.io.AbsolutePath

/**
 * Support for the .jvmopts file loaded by the sbt launcher script as alternative to command line options.
 */
object JvmOpts {

  /**
   * Tries to get jvmopts needed for running tests, which is now not possible to do with test explorer.
   * It will also try to use .jvmopts, filter out any -X options since they proved to be problematic,
   */
  def fromWorkspaceOrEnvForTest(
      workspace: AbsolutePath
  ): Option[List[String]] = {
    fromWorkspaceOrEnv(workspace, ".test-jvmopts", "TEST_JVM_OPTS").orElse(
      fromWorkspaceOrEnv(workspace).filterNot(_.startsWith("-X"))
    )
  }

  def fromEnvironment(name: String = "JVM_OPTS"): Option[List[String]] = {
    Option(System.getenv(name)) match {
      case Some(value) => Some(value.split(" ").toList)
      case None => None
    }
  }

  def fromWorkspaceOrEnv(
      workspace: AbsolutePath,
      fileName: String = ".jvmopts",
      envName: String = "JVM_OPTS",
  ): Option[List[String]] = {
    val jvmOpts = workspace.resolve(fileName)
    if (jvmOpts.isFile && Files.isReadable(jvmOpts.toNIO)) {
      val text = FileIO.slurp(jvmOpts, StandardCharsets.UTF_8)
      Some(text.linesIterator.map(_.trim).filter(_.startsWith("-")).toList)
    } else {
      fromEnvironment(envName)
    }
  }

}
